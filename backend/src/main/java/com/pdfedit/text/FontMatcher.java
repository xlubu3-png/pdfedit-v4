package com.pdfedit.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.springframework.stereotype.Component;

/**
 * Picks the font that redrawn text is set in: the page's own embedded font when it can be reused,
 * otherwise the closest font installed on this computer. No fonts are bundled with the app.
 */
@Component
public class FontMatcher {

    private static final String NO_FONT_MESSAGE = "이 PC에서 사용할 수 있는 한글 글꼴을 찾지 못했습니다. "
            + "맑은 고딕·나눔고딕 같은 글꼴을 설치한 뒤 다시 시도하세요. (Linux/Docker: fonts-nanum 패키지)";

    /** Tried, in this order, when the font matching the source font isn't installed. */
    private static final List<String> SERIF_FALLBACKS = List.of("nanummyeongjo", "batang", "unbatang", "gungsuh");
    private static final List<String> SANS_FALLBACKS =
            List.of("malgungothic", "nanumgothic", "dotum", "undotum", "gulim", "nanumbarungothic");

    private final SystemFonts systemFonts;
    private final TypefaceChooser typefaceChooser = new TypefaceChooser(this);

    public FontMatcher() {
        this(SystemFonts.system());
    }

    public FontMatcher(SystemFonts systemFonts) {
        this.systemFonts = systemFonts;
    }

    /** How many installed fonts this matcher can choose from. */
    public int installedFontCount() {
        return systemFonts.count();
    }

    /**
     * The family and weight to use for a source font. A name tells most of it, but a Type 3 font is
     * only called "T1" or "T2": its glyphs are drawing programs, so what it looks like - serif or
     * sans, bold or regular - is read from the glyphs themselves.
     */
    public Match matchFor(PDFont font, RunPainter.PageGlyphs page) {
        if (font instanceof PDType3Font) {
            return typefaceChooser.choose(font, page.samples(font));
        }
        return guess(font.getName(), font);
    }

    /** A match for an explicit style, used when comparing candidate fonts. */
    Match match(boolean serif, boolean bold) {
        return new Match(serif ? "Batang" : "맑은 고딕", bold);
    }

    /** The installed font for a match, if there is one. */
    Optional<SystemFonts.Face> faceFor(Match match) {
        return resolve(match);
    }

    /**
     * The installed font for a match, embedded as a subset into {@code outDoc}.
     *
     * @throws FontUnavailableException when no suitable font is installed on this computer
     */
    public PDFont loadFor(PDDocument outDoc, Match match) {
        SystemFonts.Face face = resolve(match).orElseThrow(() -> new FontUnavailableException(NO_FONT_MESSAGE));
        try {
            return face.embed(outDoc);
        } catch (IOException e) {
            throw new FontUnavailableException("글꼴 파일을 읽지 못했습니다: " + face.file(), e);
        }
    }

    /** A font ready to draw with; a weight the family doesn't have is imitated by thickening the strokes. */
    public record Styled(PDFont font, boolean fauxBold) {
    }

    /**
     * The font the user picked by family name, embedded as a subset into {@code outDoc}. With no family
     * the default Korean sans is used.
     *
     * @throws FontUnavailableException when the family is not installed (or no Korean font is at all)
     */
    public Styled loadStyled(PDDocument outDoc, String family, boolean bold) {
        SystemFonts.Face face;
        if (family == null || family.isBlank()) {
            face = resolve(new Match("맑은 고딕", bold)).orElseThrow(() -> new FontUnavailableException(NO_FONT_MESSAGE));
        } else {
            face = systemFonts.find(bold, family).orElseThrow(
                    () -> new FontUnavailableException("글꼴 '" + family + "'을(를) 이 PC에서 찾지 못했습니다."));
        }
        try {
            return new Styled(face.embed(outDoc), bold && !face.bold());
        } catch (IOException e) {
            throw new FontUnavailableException("글꼴 파일을 읽지 못했습니다: " + face.file(), e);
        }
    }

    /**
     * The installed font for a match with its weight forced to {@code bold}, for a run whose family
     * the user left alone but whose weight they changed.
     */
    public Styled loadMatch(PDDocument outDoc, Match match, boolean bold) {
        SystemFonts.Face face = resolve(new Match(match.cssFamily, bold))
                .orElseThrow(() -> new FontUnavailableException(NO_FONT_MESSAGE));
        try {
            return new Styled(face.embed(outDoc), bold && !face.bold());
        } catch (IOException e) {
            throw new FontUnavailableException("글꼴 파일을 읽지 못했습니다: " + face.file(), e);
        }
    }

    /** The installed families for a font list. */
    public List<SystemFonts.Family> families() {
        return systemFonts.families();
    }

    /**
     * Fails now, with a readable message, when the font has no glyph for a character of {@code text}:
     * drawing it later would silently leave a gap where the text should be.
     *
     * @throws FontUnavailableException when the font is missing or lacks a character
     */
    public void checkCovers(String family, boolean bold, String text) {
        try (PDDocument probe = new PDDocument()) {
            PDFont font = loadStyled(probe, family, bold).font();
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint)) {
                    continue;
                }
                try {
                    font.getStringWidth(new String(Character.toChars(codePoint)));
                } catch (IOException | RuntimeException missing) {
                    throw new FontUnavailableException("선택한 글꼴(" + (family == null ? "기본" : family)
                            + ")에는 '" + new String(Character.toChars(codePoint)) + "' 글자가 없습니다. 다른 글꼴을 고르세요.");
                }
            }
        } catch (IOException e) {
            throw new FontUnavailableException("글꼴을 확인하지 못했습니다.", e);
        }
    }

    public static final class Match {
        public final String cssFamily;
        public final boolean bold;

        Match(String cssFamily, boolean bold) {
            this.cssFamily = cssFamily;
            this.bold = bold;
        }

        private boolean serif() {
            return switch (cssFamily) {
                case "Batang", "Gungsuh", "나눔명조", "휴먼명조" -> true;
                default -> false;
            };
        }
    }

    /**
     * Family name for use as a CSS font-family value (resolved by the browser/OS font system).
     * When the run's actual source font is available, bold detection also checks its
     * FontDescriptor (FontWeight / ForceBold flag) instead of relying solely on the name
     * containing an English word like "bold" - Korean-named embedded fonts (e.g. "휴먼명조")
     * carry no such substring even when they genuinely are a bold weight.
     */
    public static Match guess(String pdfFontName, PDFont sourceFont) {
        if (pdfFontName == null) {
            return new Match("맑은 고딕", isBold(sourceFont));
        }
        String name = pdfFontName;
        int plus = name.indexOf('+');
        if (plus >= 0 && plus <= 8) {
            name = name.substring(plus + 1);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean bold = lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
                || isBold(sourceFont);

        String family;
        if (lower.contains("batang")) {
            family = "Batang";
        } else if (lower.contains("gungsuh")) {
            family = "Gungsuh";
        } else if (lower.contains("dotum")) {
            family = "Dotum";
        } else if (lower.contains("gulim")) {
            family = "Gulim";
        } else if (lower.contains("malgun")) {
            family = "맑은 고딕";
        } else if (lower.contains("nanum")) {
            family = lower.contains("myeongjo") ? "나눔명조" : "나눔고딕";
        } else if (name.contains("휴먼명조")) {
            family = "휴먼명조";
        } else if (name.contains("휴먼고딕")) {
            family = "휴먼고딕";
        } else if (name.contains("명조")) {
            // Other Korean-named serif fonts (e.g. "HY신명조") never match the Latin keywords
            // above: HWP-generated PDFs embed fonts under their literal Korean names.
            family = "나눔명조";
        } else {
            family = "맑은 고딕";
        }
        return new Match(family, bold);
    }

    /**
     * Reads boldness straight from the font's own FontDescriptor (FontWeight >= 600, or the
     * ForceBold flag) rather than the font's declared name.
     */
    private static boolean isBold(PDFont sourceFont) {
        if (sourceFont == null) {
            return false;
        }
        try {
            PDFontDescriptor descriptor = sourceFont instanceof PDType0Font type0
                    ? (type0.getDescendantFont() != null ? type0.getDescendantFont().getFontDescriptor() : null)
                    : sourceFont.getFontDescriptor();
            if (descriptor == null) {
                return false;
            }
            return descriptor.getFontWeight() >= 600 || descriptor.isForceBold();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the exact TrueType program embedded for this run's own original font and
     * re-embeds it under a fresh, randomly-tagged name - a pixel-accurate match instead of a
     * substitute. Returns null (caller falls back to {@link #loadForExport}) when the source
     * font isn't embedded, is embedded as CFF/OpenType-CFF/Type1 rather than TrueType, has no
     * cmap table (typical of subset fonts), or extraction otherwise fails.
     *
     * <p>The rename is not cosmetic: reusing the same font bytes reproduces the same BaseFont
     * name as the original page's own font resource, and stricter viewers (Chromium's PDFium)
     * resolve fonts by declared name rather than by object reference - which silently corrupted
     * unrelated, unedited text on the page. A unique subset tag, the same mechanism real PDF
     * producers use to distinguish multiple subsets of one family, sidesteps that collision.
     */
    public static PDFont tryLoadOriginalEmbedded(PDDocument outDoc, PDFont sourceFont) {
        if (sourceFont == null) {
            return null;
        }
        try {
            PDFontDescriptor descriptor;
            if (sourceFont instanceof PDType0Font type0) {
                PDCIDFont descendant = type0.getDescendantFont();
                descriptor = descendant != null ? descendant.getFontDescriptor() : null;
            } else {
                descriptor = sourceFont.getFontDescriptor();
            }
            if (descriptor == null) {
                return null;
            }
            PDStream fontFile2 = descriptor.getFontFile2();
            if (fontFile2 == null) {
                return null;
            }
            byte[] bytes;
            try (InputStream is = fontFile2.createInputStream()) {
                bytes = is.readAllBytes();
            }
            // isEmbedded=true is FontBox's accommodation for subset fonts that lack tables (e.g.
            // 'name') a standalone system font must have but a PDF never needs.
            TrueTypeFont ttf = new TTFParser(true).parse(new RandomAccessReadBuffer(bytes));
            PDType0Font embedded = PDType0Font.load(outDoc, ttf, false);
            renameToAvoidCollision(embedded, sourceFont.getName());
            return embedded;
        } catch (Exception e) {
            return null;
        }
    }

    private static void renameToAvoidCollision(PDType0Font font, String originalName) {
        String uniqueName = randomSubsetTag() + "+" + stripExistingTag(originalName);
        font.getCOSObject().setName(COSName.BASE_FONT, uniqueName);
        PDCIDFont descendant = font.getDescendantFont();
        if (descendant != null) {
            descendant.getCOSObject().setName(COSName.BASE_FONT, uniqueName);
            PDFontDescriptor fd = descendant.getFontDescriptor();
            if (fd != null) {
                fd.setFontName(uniqueName);
            }
        }
    }

    private static String stripExistingTag(String name) {
        if (name == null || name.isEmpty()) {
            return "Font";
        }
        int plus = name.indexOf('+');
        return (plus == 6) ? name.substring(plus + 1) : name;
    }

    private static String randomSubsetTag() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return sb.toString();
    }

    /**
     * The installed font closest to the source font, embedded in full into {@code outDoc}.
     *
     * @throws FontUnavailableException when no suitable font is installed on this computer
     */
    public PDFont loadForExport(PDDocument outDoc, String pdfFontName, PDFont sourceFont) {
        return loadFor(outDoc, guess(pdfFontName, sourceFont));
    }

    /**
     * Fails fast, before an edit is stored, when text in this run could not be redrawn later:
     * neither the page's own font nor an installed font is usable.
     *
     * @throws FontUnavailableException when no font can draw text for this run
     */
    public void checkAvailable(String pdfFontName, PDFont sourceFont) {
        try (PDDocument probe = new PDDocument()) {
            if (tryLoadOriginalEmbedded(probe, sourceFont) != null) {
                return;
            }
        } catch (IOException ignored) {
            // fall through to the installed fonts
        }
        if (resolve(guess(pdfFontName, sourceFont)).isEmpty()) {
            throw new FontUnavailableException(NO_FONT_MESSAGE);
        }
    }

    private Optional<SystemFonts.Face> resolve(Match match) {
        return exactFamily(match)
                .or(() -> firstInstalled(match.bold, match.serif() ? SERIF_FALLBACKS : SANS_FALLBACKS))
                .or(() -> firstInstalled(match.bold, match.serif() ? SANS_FALLBACKS : SERIF_FALLBACKS));
    }

    private Optional<SystemFonts.Face> exactFamily(Match match) {
        boolean bold = match.bold;
        return switch (match.cssFamily) {
            // Windows ships these as 4-font collections with no bold cut; the fixed-width "Che"
            // sibling stands in for bold, as it always has.
            case "Batang" -> withBoldStandIn(bold, new String[] {"batang", "바탕"}, new String[] {"batangche", "바탕체"});
            case "Gungsuh" -> withBoldStandIn(bold, new String[] {"gungsuh", "궁서"}, new String[] {"gungsuhche", "궁서체"});
            case "Dotum" -> withBoldStandIn(bold, new String[] {"dotum", "돋움"}, new String[] {"dotumche", "돋움체"});
            case "Gulim" -> withBoldStandIn(bold, new String[] {"gulim", "굴림"}, new String[] {"gulimche", "굴림체"});
            case "맑은 고딕" -> systemFonts.find(bold, "malgungothic", "맑은고딕");
            case "나눔고딕" -> systemFonts.find(bold, "nanumgothic", "나눔고딕");
            case "나눔명조" -> systemFonts.find(bold, "nanummyeongjo", "나눔명조");
            case "휴먼명조" -> systemFonts.findContaining(bold, "휴먼명조", "humanmyeongjo");
            case "휴먼고딕" -> systemFonts.findContaining(bold, "휴먼고딕", "humangothic");
            default -> Optional.empty();
        };
    }

    private Optional<SystemFonts.Face> withBoldStandIn(boolean bold, String[] regular, String[] boldStandIn) {
        if (bold) {
            Optional<SystemFonts.Face> standIn = systemFonts.find(false, boldStandIn);
            if (standIn.isPresent()) {
                return standIn;
            }
        }
        return systemFonts.find(false, regular);
    }

    private Optional<SystemFonts.Face> firstInstalled(boolean bold, List<String> names) {
        for (String name : names) {
            Optional<SystemFonts.Face> face = systemFonts.find(bold, name);
            if (face.isPresent()) {
                return face;
            }
        }
        return Optional.empty();
    }
}
