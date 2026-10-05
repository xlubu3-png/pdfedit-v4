package com.pdfedit.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.pdfedit.dto.AppInfo;
import com.pdfedit.dto.FontChoice;
import com.pdfedit.text.FontMatcher;

@RestController
@RequestMapping("/api/v1/pdf")
public class AppInfoController {

    private final String version;
    private final FontMatcher fontMatcher;

    public AppInfoController(@Value("${app.version}") String version, FontMatcher fontMatcher) {
        this.version = version;
        this.fontMatcher = fontMatcher;
    }

    @GetMapping("/info")
    public AppInfo info() {
        return new AppInfo(version, fontMatcher.installedFontCount());
    }

    /**
     * Reads the font folders again, for a font the user installed while the app was running, and says
     * how many fonts there are now. Takes a second or two.
     */
    @PostMapping("/fonts/reload")
    public AppInfo reloadFonts() {
        return new AppInfo(version, fontMatcher.reloadFonts());
    }

    /** The font families installed on this computer, Korean ones first, for the font pickers. */
    @GetMapping("/fonts")
    public List<FontChoice> fonts() {
        return fontMatcher.families().stream()
                .map(f -> new FontChoice(f.name(), f.label(), f.korean()))
                .toList();
    }

    /** Reading the font folders takes a second or two; do it now so the first request isn't the one waiting. */
    @EventListener(ApplicationReadyEvent.class)
    void warmUpFontIndex() {
        Thread.ofVirtual().start(fontMatcher::installedFontCount);
    }
}
