package com.pdfedit.text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps what was learned about the page fonts on disk ({@code font-analysis.bin} in the data folder): which
 * installed font each nameless page font looks like, and the installed fonts' letters drawn to tell. Without it
 * every restart drew them all again, and the first page of a document set in Type 3 fonts (HWP output) took
 * seconds to open. The file holds only for the fonts installed when it was written; when they change it is
 * set aside and learned anew.
 */
@Component
public class FontAnalysisStore implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FontAnalysisStore.class);

    static final String FILE = "font-analysis.bin";

    private final Path file;
    private final FontMatcher fontMatcher;
    private volatile String fonts;

    @Autowired
    public FontAnalysisStore(@Value("${app.data-dir}") String dataDir, FontMatcher fontMatcher) {
        this(Path.of(dataDir).resolve(FILE), fontMatcher);
    }

    FontAnalysisStore(Path file, FontMatcher fontMatcher) {
        this.file = file;
        this.fontMatcher = fontMatcher;
    }

    /** Read back in the background once the app is up: finding the installed fonts takes a moment. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadInBackground() {
        Thread.ofPlatform().daemon().name("font-analysis-load").start(this::load);
    }

    /** @return how many results were read */
    int load() {
        try {
            int read = TypefaceChooser.load(file, fonts());
            if (read > 0) {
                log.info("글꼴 분석 결과 {}개를 불러왔습니다", read);
            }
            return read;
        } catch (RuntimeException e) {
            log.warn("글꼴 분석 결과를 불러오지 못했습니다: {}", e.toString());
            return 0;
        }
    }

    /** Written now and then, when something new was learned. */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void save() {
        try {
            TypefaceChooser.save(file, fonts());
        } catch (IOException | RuntimeException e) {
            log.warn("글꼴 분석 결과를 저장하지 못했습니다: {}", e.toString());
        }
    }

    @Override
    public void destroy() {
        save();
    }

    /** What the installed fonts the results were learned from are: their files, sizes and dates. */
    private String fonts() {
        String known = fonts;
        if (known != null) {
            return known;
        }
        Set<String> seen = new TreeSet<>();
        for (SystemFonts.Face face : fontMatcher.koreanFaces()) {
            seen.add(stamp(face));
        }
        for (boolean serif : new boolean[] {false, true}) {
            for (boolean bold : new boolean[] {false, true}) {
                fontMatcher.faceFor(fontMatcher.match(serif, bold)).ifPresent(face -> seen.add(stamp(face)));
            }
        }
        known = Integer.toHexString(String.join("\n", seen).hashCode()) + ":" + seen.size();
        fonts = known;
        return known;
    }

    private static String stamp(SystemFonts.Face face) {
        try {
            return face.file() + "|" + face.collectionFontName() + "|" + Files.size(face.file()) + "|"
                    + Files.getLastModifiedTime(face.file()).toMillis();
        } catch (IOException e) {
            return face.file() + "|" + face.collectionFontName();
        }
    }
}
