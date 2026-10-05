package com.pdfedit.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.fontbox.ttf.NameRecord;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The TrueType fonts installed on this computer, indexed by the names stored inside the font files
 * (family, full and PostScript names, including localized ones such as "맑은 고딕") rather than by
 * file name, so fonts the user installed are found as well as the ones the OS ships.
 *
 * <p>The font folders are scanned once, on first use.
 */
public final class SystemFonts {

    private static final int MAX_DEPTH = 5;
    private static final int NAME_FAMILY = 1;
    private static final int NAME_SUBFAMILY = 2;
    private static final int LANGUAGE_ENGLISH_US = 0x409;
    private static final int LANGUAGE_KOREAN = 0x412;
    /** Families made for Korean that carry no Korean name in the font file. */
    private static final List<String> KOREAN_FAMILIES = List.of("malgun", "nanum", "batang", "dotum", "gulim",
            "gungsuh", "notosanscjk", "notoserifcjk", "applesdgothic", "pretendard", "spoqa", "kopub", "hcr", "hygothic",
            "hymyeongjo", "hysinmyeongjo");
    private static final int NAME_FULL = 4;
    private static final int NAME_POSTSCRIPT = 6;
    private static final int NAME_TYPOGRAPHIC_FAMILY = 16;

    private static final SystemFonts SYSTEM = new SystemFonts(defaultRoots());

    private final List<Path> roots;
    private volatile List<Face> faces;

    public SystemFonts(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    /** The fonts installed on this machine. */
    public static SystemFonts system() {
        return SYSTEM;
    }

    /** Number of fonts found in the font folders (a .ttc collection counts each of its fonts). */
    public int count() {
        return faces().size();
    }

    /**
     * One font installed on the machine.
     *
     * @param collectionFontName PostScript name of the member inside a .ttc collection, or null for a
     *                           standalone .ttf file
     * @param names              normalized family/full/PostScript names (see {@link #normalize})
     * @param italic             the font is slanted
     * @param plain              the style is plain Regular or Bold (not Light, Semibold, ...)
     * @param family             family name for display and selection, English when the font has one
     * @param label              what a font list shows: the Korean family name first, when there is one
     * @param korean             made for Hangul (Korean name, or a well-known Korean family)
     */
    public record Face(Path file, String collectionFontName, boolean bold, boolean italic, boolean plain,
                       String family, String label, boolean korean, Set<String> names) {

        /** Lower is better when several fonts of one family could serve: upright and plain first. */
        int rank() {
            return (italic ? 2 : 0) + (plain ? 0 : 1);
        }

        public boolean inCollection() {
            return collectionFontName != null;
        }

        public InputStream openStandalone() throws IOException {
            return Files.newInputStream(file);
        }

        /**
         * Embeds the font as a subset: only the glyphs actually drawn end up in the PDF, which keeps
         * exports small. (PDFBox cannot embed a font from a .ttc collection in full, so for those
         * subsetting is the only option anyway.)
         */
        public PDType0Font embed(PDDocument doc) throws IOException {
            return embed(doc, true);
        }

        PDType0Font embed(PDDocument doc, boolean subset) throws IOException {
            try (InputStream in = openStandalone()) {
                if (collectionFontName == null) {
                    return PDType0Font.load(doc, in, subset);
                }
                // Reading the collection from a stream loads it into memory, so no file handle stays
                // open; the subset is written when the document is saved.
                TrueTypeFont font = new TrueTypeCollection(in).getFontByName(collectionFontName);
                if (font == null) {
                    throw new IOException("Font " + collectionFontName + " not found in " + file);
                }
                doc.registerTrueTypeFontForClosing(font);
                return PDType0Font.load(doc, font, subset);
            }
        }
    }

    /**
     * A font with any of the given names (matched ignoring case, spaces and hyphens). Prefers the
     * requested weight, but falls back to the other one when only that exists.
     */
    public Optional<Face> find(boolean bold, String... names) {
        Set<String> wanted = Arrays.stream(names).map(SystemFonts::normalize).collect(Collectors.toSet());
        return pick(bold, face -> face.names().stream().anyMatch(wanted::contains));
    }

    /** Like {@link #find} but a name only has to contain one of the fragments. */
    public Optional<Face> findContaining(boolean bold, String... fragments) {
        List<String> wanted = Arrays.stream(fragments).map(SystemFonts::normalize).toList();
        return pick(bold, face -> face.names().stream().anyMatch(name -> wanted.stream().anyMatch(name::contains)));
    }

    private Optional<Face> pick(boolean bold, Predicate<Face> matches) {
        List<Face> candidates = faces().stream().filter(matches).toList();
        Comparator<Face> best = Comparator.comparingInt(Face::rank);
        return candidates.stream().filter(face -> face.bold() == bold).sorted(best).findFirst()
                .or(() -> candidates.stream().sorted(best).findFirst());
    }

    /** A font family to offer in a list; {@code name} selects it again through {@link #find}. */
    public record Family(String name, String label, boolean korean) {
    }

    /** The installed families, Korean ones first, each once however many styles it has. */
    public List<Family> families() {
        Map<String, Family> byName = new LinkedHashMap<>();
        for (Face face : faces()) {
            byName.merge(normalize(face.family()), new Family(face.family(), face.label(), face.korean()),
                    (a, b) -> new Family(a.name(), a.label(), a.korean() || b.korean()));
        }
        return byName.values().stream()
                .sorted(Comparator.comparing((Family f) -> !f.korean())
                        .thenComparing(f -> f.label().toLowerCase(Locale.ROOT)))
                .toList();
    }

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]", "");
    }

    private List<Face> faces() {
        List<Face> local = faces;
        if (local == null) {
            synchronized (this) {
                local = faces;
                if (local == null) {
                    local = scan();
                    faces = local;
                }
            }
        }
        return local;
    }

    private List<Face> scan() {
        List<Face> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root, MAX_DEPTH)) {
                files.filter(Files::isRegularFile).sorted().forEach(file -> readFaces(file, found));
            } catch (IOException | UncheckedIOException unreadableFolder) {
                // keep whatever the other folders provide
            }
        }
        return List.copyOf(found);
    }

    private static void readFaces(Path file, List<Face> out) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (fileName.endsWith(".ttf")) {
                // FontBox reads table contents lazily, so only the tables asked for here (name,
                // head) are loaded and indexing a folder of large CJK fonts stays quick.
                try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBufferedFile(file))) {
                    out.add(describe(file, null, font));
                }
            } else if (fileName.endsWith(".ttc")) {
                try (TrueTypeCollection collection = new TrueTypeCollection(file.toFile())) {
                    collection.processAllFonts(font -> out.add(describe(file, font.getName(), font)));
                }
            }
        } catch (Exception unreadableFont) {
            // not a usable TrueType font: skip it
        }
    }

    private static Face describe(Path file, String collectionFontName, TrueTypeFont font) throws IOException {
        Set<String> names = new HashSet<>();
        String englishTypographic = null;
        String englishFamily = null;
        String koreanName = null;
        String anyFamily = null;
        String subfamily = null;
        boolean hangulInAnyName = false;
        if (font.getNaming() != null) {
            for (NameRecord record : font.getNaming().getNameRecords()) {
                int id = record.getNameId();
                String value = record.getString();
                boolean wanted = id == NAME_FAMILY || id == NAME_FULL || id == NAME_POSTSCRIPT
                        || id == NAME_TYPOGRAPHIC_FAMILY;
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (wanted) {
                    names.add(normalize(value));
                    hangulInAnyName |= value.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
                }
                int language = record.getLanguageId();
                if (id == NAME_FAMILY || id == NAME_TYPOGRAPHIC_FAMILY) {
                    anyFamily = anyFamily == null || id == NAME_TYPOGRAPHIC_FAMILY ? value : anyFamily;
                    if (language == LANGUAGE_ENGLISH_US) {
                        if (id == NAME_TYPOGRAPHIC_FAMILY) {
                            englishTypographic = value;
                        } else {
                            englishFamily = value;
                        }
                    } else if (language == LANGUAGE_KOREAN && (koreanName == null || id == NAME_TYPOGRAPHIC_FAMILY)) {
                        koreanName = value;
                    }
                } else if (id == NAME_SUBFAMILY && (subfamily == null || language == LANGUAGE_ENGLISH_US)) {
                    subfamily = value;
                }
            }
        }
        boolean bold = font.getHeader() != null && (font.getHeader().getMacStyle() & 1) != 0;
        boolean italic = font.getHeader() != null && (font.getHeader().getMacStyle() & 2) != 0;
        String style = subfamily == null ? "" : normalize(subfamily);
        boolean plain = style.isEmpty() ? !bold && !italic : style.equals("regular") || style.equals("bold");

        String family = englishTypographic != null ? englishTypographic
                : englishFamily != null ? englishFamily
                : anyFamily != null ? anyFamily : font.getName();
        String label = koreanName != null && !normalize(koreanName).equals(normalize(family))
                ? koreanName + " (" + family + ")" : family;
        boolean korean = koreanName != null || hangulInAnyName || KOREAN_FAMILIES.stream()
                .anyMatch(fragment -> normalize(family).contains(fragment));
        return new Face(file, collectionFontName, bold, italic, plain, family, label, korean, Set.copyOf(names));
    }

    /**
     * Folders listed in {@code PDFEDIT_FONT_DIRS} (separated like PATH), for fonts mounted somewhere
     * the OS doesn't look - e.g. a container reading its host's fonts. Kept out of the usual font
     * folders on purpose: PDFBox scans those by itself to find fallback fonts, and on a slow mount
     * that scan can take minutes.
     */
    static List<Path> extraRoots(String setting) {
        List<Path> roots = new ArrayList<>();
        if (setting != null) {
            for (String part : setting.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) {
                    roots.add(Path.of(part.trim()));
                }
            }
        }
        return roots;
    }

    private static List<Path> defaultRoots() {
        List<Path> roots = new ArrayList<>(extraRoots(System.getenv("PDFEDIT_FONT_DIRS")));
        String windowsDir = System.getenv("WINDIR");
        if (windowsDir != null) {
            roots.add(Path.of(windowsDir, "Fonts"));
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            roots.add(Path.of(localAppData, "Microsoft", "Windows", "Fonts"));
        }
        String home = System.getProperty("user.home");
        roots.add(Path.of("/usr/share/fonts"));
        roots.add(Path.of("/usr/local/share/fonts"));
        roots.add(Path.of("/System/Library/Fonts"));
        roots.add(Path.of("/Library/Fonts"));
        if (home != null) {
            roots.add(Path.of(home, ".fonts"));
            roots.add(Path.of(home, ".local", "share", "fonts"));
            roots.add(Path.of(home, "Library", "Fonts"));
        }
        return roots;
    }
}
