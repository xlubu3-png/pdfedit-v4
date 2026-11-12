package com.pdfedit.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.TextRunDto;
import com.pdfedit.service.PageEdits;
import com.pdfedit.service.TextEditService;

import ai.onnxruntime.OrtException;

/**
 * Recognizes the text of a page that is a picture (a scan): the page is drawn at {@value #DPI} dpi and read by
 * the {@link OcrEngine}, and every line found is placed back on the page in points. Lines where the page
 * already has real text are left out, so a page that is partly text, partly picture gets only the picture's
 * words.
 */
@Service
public class PageOcr implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PageOcr.class);

    /** Enough for print down to 8 pt: the models read text from about 15 pixels high. */
    static final float DPI = 200f;
    /** Telling a page's way up needs only its lines' shapes and a few of them read: a lighter picture will do. */
    private static final float ORIENT_DPI = 120f;
    /** How far (points) around a real text line an OCR line still counts as the same text. */
    private static final float SAME_TEXT_MARGIN = 2f;

    /**
     * A page's tilt is told by its lines across at least this many times longer than tall, when it has at least
     * {@link #TILT_LINES} of them; less than {@link #LEAST_TILT} degrees is no tilt, more than {@link #MOST_TILT} is
     * not a sheet fed askew (a page drawn turned, which the lines' corners tell poorly).
     */
    private static final double LONG_LINE = 6;
    private static final int TILT_LINES = 1;
    private static final double LEAST_TILT = 0.15;
    private static final double MOST_TILT = 5;

    private final TextEditService textEditService;
    private OcrEngine engine;
    private final AtomicBoolean prepared = new AtomicBoolean();

    public PageOcr(TextEditService textEditService) {
        this.textEditService = textEditService;
    }

    /**
     * @param lines       the recognized lines, top to bottom
     * @param alreadyText lines left out because the page has real text there
     * @param seconds     how long the recognition took
     */
    public record Result(List<OcrText> lines, int alreadyText, double seconds) {
    }

    /** @throws OcrUnavailableException when the recognition engine cannot run on this computer */
    public Result recognize(byte[] pdf, int pageIndex) throws IOException {
        long start = System.nanoTime();
        BufferedImage picture;
        PDRectangle crop;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            crop = doc.getPage(pageIndex).getCropBox();
            picture = new PDFRenderer(doc).renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
        }
        long drawn = System.nanoTime();
        List<TextRunDto> runs = textEditService.describePage(pdf, pageIndex, PageEdits.NONE).runs();
        long described = System.nanoTime();
        List<OcrLine> read;
        try {
            // Read off the picture cleaned where it is faint or speckled; the places found are the same.
            BufferedImage cleaned = ScanCleanup.forReading(picture);
            log.debug("page {} for reading: drawn {} ms, its text {} ms, cleaned {} ms", pageIndex, (drawn - start) / 1_000_000,
                    (described - drawn) / 1_000_000, (System.nanoTime() - described) / 1_000_000);
            read = engine().read(cleaned);
        } catch (OrtException e) {
            throw new OcrUnavailableException(e);
        }
        float k = 72f / DPI;
        Float tilt = tilt(read);
        List<OcrText> lines = new ArrayList<>();
        int alreadyText = 0;
        for (OcrLine line : read) {
            float x = crop.getLowerLeftX() + line.left() * k;
            float top = crop.getUpperRightY() - line.top() * k;
            float bottom = crop.getUpperRightY() - line.bottom() * k;
            List<Float> centers = new ArrayList<>(line.centers().length);
            for (float c : line.centers()) {
                centers.add(Math.round(c * 1000) / 1000f);
            }
            OcrText text = new OcrText(line.text(), x, bottom, (line.right() - line.left()) * k, top - bottom,
                    line.score(), centers, line.orientation(), line.orientation() == null ? tilt : null);
            if (onRealText(text, runs)) {
                alreadyText++;
            } else {
                lines.add(text);
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        // One line a page, kept in the log the problem report sends: how long a page took on the user's PC.
        log.info("글자 인식 {}쪽: {} ms ({}줄)", pageIndex + 1, Math.round(seconds * 1000), lines.size());
        return new Result(lines, alreadyText, seconds);
    }

    /**
     * Quarter turns clockwise (0, 90, 180, 270) that set a page scanned on its side or upside down upright, as the
     * reader tells from its lines; 0 when it is upright or cannot be told.
     */
    public int uprightTurn(byte[] pdf, int pageIndex) throws IOException {
        long t0 = System.nanoTime();
        BufferedImage picture;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            picture = new PDFRenderer(doc).renderImageWithDPI(pageIndex, ORIENT_DPI, ImageType.RGB);
        }
        long t1 = System.nanoTime();
        try {
            BufferedImage cleaned = ScanCleanup.forReading(picture);
            long t2 = System.nanoTime();
            int turn = engine().uprightTurn(cleaned);
            log.debug("way up of page {}: drawn {} ms, cleaned {} ms, told {} ms", pageIndex, (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000, (System.nanoTime() - t2) / 1_000_000);
            return turn;
        } catch (OrtException e) {
            throw new OcrUnavailableException(e);
        }
    }

    /**
     * How far the page is turned, in degrees counter-clockwise: the middle of the slopes of its long lines across
     * (a scanner turns all of a sheet alike, and a long line's corners tell its slope best); null when it has too
     * few such lines, or is turned too little to matter.
     */
    static Float tilt(List<OcrLine> lines) {
        List<Double> slopes = new ArrayList<>();
        for (OcrLine line : lines) {
            float[] c = line.corners();
            double run = c[2] - c[0];
            if (line.orientation() == null && run >= LONG_LINE * Math.max(1, line.bottom() - line.top())) {
                // The top edge, from the top-left corner to the top-right; the picture's y runs down.
                slopes.add(-Math.toDegrees(Math.atan2(c[3] - c[1], run)));
            }
        }
        if (slopes.size() < TILT_LINES) {
            return null;
        }
        slopes.sort(null);
        double middle = slopes.get(slopes.size() / 2);
        return Math.abs(middle) < LEAST_TILT || Math.abs(middle) > MOST_TILT ? null
                : Math.round(middle * 100) / 100f;
    }

    private static boolean onRealText(OcrText line, List<TextRunDto> runs) {
        float cx = line.x() + line.width() / 2;
        float cy = line.y() + line.height() / 2;
        for (TextRunDto run : runs) {
            if (cx >= run.x() - SAME_TEXT_MARGIN && cx <= run.x() + run.width() + SAME_TEXT_MARGIN
                    && cy >= run.boxBottom() - SAME_TEXT_MARGIN && cy <= run.boxTop() + SAME_TEXT_MARGIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts the recognition engine in the background, once per run of the app: called when a scan is opened, so
     * that recognizing its first page does not also wait for the models to load and run for the first time (about
     * half of the first page's five seconds). Quietly does nothing more if the engine cannot run here; recognizing
     * then says so.
     */
    public void prepareInBackground() {
        if (!prepared.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("ocr-warm-up").start(() -> {
            try {
                engine().read(warmUpPicture());
            } catch (OrtException | RuntimeException | LinkageError e) {
                log.info("OCR warm-up skipped: {}", e.toString());
            }
        });
    }

    /** A line of text to read once, so the detection and the reading both run before the first real page. */
    private static BufferedImage warmUpPicture() {
        BufferedImage image = new BufferedImage(640, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
            g.drawString("문서 글자 인식 2023", 30, 95);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Loaded on first use: the models take a second or two and some memory, and most documents never need them. */
    private synchronized OcrEngine engine() {
        if (engine == null) {
            try {
                engine = OcrEngine.load();
            } catch (IOException | OrtException | RuntimeException | UnsatisfiedLinkError e) {
                log.error("The OCR engine could not be started", e);
                throw new OcrUnavailableException(e);
            }
        }
        return engine;
    }

    @Override
    public synchronized void destroy() throws OrtException {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
