package com.pdfedit.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a document smaller by storing its pictures again, smaller: what a scanned PDF (tens of megabytes) needs to
 * fit a mail or an upload form.
 *
 * <p>A picture is never kept with more pixels than {@value #DPI} dpi of the largest page needs (a picture is never
 * shown bigger than its page, so this never makes one blurrier than that). Photos and scans are stored as JPEG; a
 * picture of few colours (a chart, a screenshot, line art) keeps lossless storage, where JPEG would smear its edges,
 * and only loses pixels it does not need. Left as they are: small pictures (icons, signatures), black-and-white
 * scans (already stored tightly), pictures with transparency and pictures in colours JPEG cannot hold. A picture is
 * only replaced when that saves at least {@value #LEAST_SAVING_PERCENT}%. The text and drawings of the pages are
 * not touched.
 */
public final class ImageShrinker {

    private static final Logger log = LoggerFactory.getLogger(ImageShrinker.class);

    /** The resolution kept: sharp on screen and in print of an office document. */
    public static final float DPI = 150f;
    /** JPEG quality of photos and scans. */
    static final float QUALITY = 0.75f;
    /** Smaller pictures than this (about 500 x 500) are left alone: they weigh little and are often logos. */
    static final long LEAST_PIXELS = 250_000;
    static final int LEAST_SAVING_PERCENT = 5;
    /** Distinct colours (of a sample) above which a picture counts as a photo or scan; for a gray one, gray levels. */
    static final int PHOTO_COLOURS = 1500;
    static final int PHOTO_GRAYS = 48;
    /** Bigger pictures than this are left alone: drawing one out could take more memory than the app has. */
    static final long MOST_PIXELS = 60_000_000;

    private static final Set<COSName> TIGHT_FILTERS = Set.of(COSName.JBIG2_DECODE, COSName.CCITTFAX_DECODE,
            COSName.JPX_DECODE);

    /**
     * What shrinking did.
     *
     * @param pictures pictures stored again
     * @param before   their stored size before, bytes
     * @param after    their stored size after, bytes
     */
    public record Report(int pictures, long before, long after) {
        public static final Report NONE = new Report(0, 0, 0);
    }

    private ImageShrinker() {
    }

    /** Stores the pictures of {@code document} again, smaller, where that is worth it. */
    public static Report shrink(PDDocument document) {
        float longestSide = 0;
        for (PDPage page : document.getPages()) {
            PDRectangle box = page.getMediaBox();
            longestSide = Math.max(longestSide, Math.max(box.getWidth(), box.getHeight()));
        }
        int mostPixels = Math.max(1, Math.round(longestSide / 72f * DPI));
        Shrinking shrinking = new Shrinking(document, mostPixels);
        for (PDPage page : document.getPages()) {
            shrinking.resources(page.getResources(), 0);
        }
        return new Report(shrinking.pictures, shrinking.before, shrinking.after);
    }

    private static final class Shrinking {
        private final PDDocument document;
        private final int mostPixels;
        /** Each picture once, however many pages use it: its stream, and what replaces it (itself when kept). */
        private final Map<COSStream, PDImageXObject> done = new IdentityHashMap<>();
        private final Set<COSBase> seenResources = Collections.newSetFromMap(new IdentityHashMap<>());
        int pictures;
        long before;
        long after;

        Shrinking(PDDocument document, int mostPixels) {
            this.document = document;
            this.mostPixels = mostPixels;
        }

        void resources(PDResources resources, int depth) {
            if (resources == null || depth > 16 || !seenResources.add(resources.getCOSObject())) {
                return;
            }
            for (COSName name : new HashSet<>(toList(resources.getXObjectNames()))) {
                PDXObject object;
                try {
                    object = resources.getXObject(name);
                } catch (IOException | RuntimeException unreadable) {
                    continue;
                }
                if (object instanceof PDImageXObject image) {
                    PDImageXObject replacement = done.computeIfAbsent(image.getCOSObject(), stream -> smaller(image));
                    if (replacement != image) {
                        resources.put(name, replacement);
                    }
                } else if (object instanceof PDFormXObject form) {
                    resources(form.getResources(), depth + 1);
                }
            }
        }

        /** The picture stored smaller, or the picture itself when that is not worth it (or not possible). */
        private PDImageXObject smaller(PDImageXObject image) {
            try {
                if (!candidate(image)) {
                    return image;
                }
                BufferedImage picture = image.getImage();
                // A gray picture stays gray: stored as colour it would take three times the room.
                boolean gray = image.getColorSpace().getNumberOfComponents() == 1;
                if (gray && picture.getType() != BufferedImage.TYPE_BYTE_GRAY) {
                    picture = draw(picture, picture.getWidth(), picture.getHeight(), true);
                }
                int longest = Math.max(picture.getWidth(), picture.getHeight());
                if (longest > mostPixels) {
                    double scale = (double) mostPixels / longest;
                    picture = scaled(picture, Math.max(1, (int) Math.round(picture.getWidth() * scale)),
                            Math.max(1, (int) Math.round(picture.getHeight() * scale)), gray);
                }
                boolean photo = isJpeg(image) || colours(picture) > (gray ? PHOTO_GRAYS : PHOTO_COLOURS);
                PDImageXObject stored = photo ? JPEGFactory.createFromImage(document, picture, QUALITY)
                        : LosslessFactory.createFromImage(document, picture);
                long was = image.getCOSObject().getLength();
                long now = stored.getCOSObject().getLength();
                if (now * 100 > was * (100L - LEAST_SAVING_PERCENT)) {
                    return image;
                }
                pictures++;
                before += was;
                after += now;
                return stored;
            } catch (IOException | RuntimeException e) {
                // A picture that cannot be read or stored again stays as it was; the rest still shrink.
                log.info("A picture was left as it was: {}", e.toString());
                return image;
            }
        }
    }

    /** Whether storing the picture again could make it smaller without spoiling it. */
    private static boolean candidate(PDImageXObject image) throws IOException {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels < LEAST_PIXELS || pixels > MOST_PIXELS || image.isStencil()
                || image.getBitsPerComponent() == 1) {
            return false;
        }
        COSStream stream = image.getCOSObject();
        if (stream.containsKey(COSName.SMASK) || stream.containsKey(COSName.MASK)
                || stream.containsKey(COSName.DECODE)) {
            return false;
        }
        for (COSName filter : filters(stream)) {
            if (TIGHT_FILTERS.contains(filter)) {
                return false;
            }
        }
        // Colours JPEG holds as they are; other ones (CMYK, spot colours, palettes) would change on the way.
        PDColorSpace space = image.getColorSpace();
        if (space instanceof PDICCBased icc) {
            return icc.getNumberOfComponents() == 1 || icc.getNumberOfComponents() == 3;
        }
        return space instanceof PDDeviceRGB || space instanceof PDDeviceGray;
    }

    private static boolean isJpeg(PDImageXObject image) {
        return filters(image.getCOSObject()).contains(COSName.DCT_DECODE);
    }

    private static List<COSName> filters(COSStream stream) {
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter instanceof COSName name) {
            return List.of(name);
        }
        if (filter instanceof COSArray array) {
            return array.toList().stream().map(b -> b instanceof COSName n ? n : null)
                    .filter(java.util.Objects::nonNull).toList();
        }
        return List.of();
    }

    /** Distinct colours in a sample of up to 10,000 pixels spread over the picture (stops counting past photo level). */
    static int colours(BufferedImage picture) {
        int step = Math.max(1, (int) Math.sqrt((double) picture.getWidth() * picture.getHeight() / 10_000));
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < picture.getHeight(); y += step) {
            for (int x = 0; x < picture.getWidth(); x += step) {
                seen.add(picture.getRGB(x, y) & 0xFFFFFF);
                if (seen.size() > PHOTO_COLOURS) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    /** Smaller, in halving steps then one last one, so that fine lines and text do not break up into steps. */
    static BufferedImage scaled(BufferedImage picture, int width, int height, boolean gray) {
        BufferedImage current = picture;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2, gray);
        }
        return current.getWidth() == width && current.getHeight() == height ? current : draw(current, width, height, gray);
    }

    private static BufferedImage draw(BufferedImage from, int width, int height, boolean gray) {
        BufferedImage to = new BufferedImage(width, height, gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = to.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(from, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return to;
    }

    private static <T> List<T> toList(Iterable<T> items) {
        List<T> list = new java.util.ArrayList<>();
        items.forEach(list::add);
        return list;
    }
}
