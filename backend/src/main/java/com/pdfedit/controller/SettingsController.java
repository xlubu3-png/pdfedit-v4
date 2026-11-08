package com.pdfedit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pdfedit.dto.AppSettings;
import com.pdfedit.service.PdfDocumentStore;

/** The settings the user can change on the screen, kept across restarts. */
@RestController
@RequestMapping("/api/v1/pdf/settings")
public class SettingsController {

    static final String STRAIGHTEN_ON_OCR = "straightenOnOcr";

    private final PdfDocumentStore store;

    public SettingsController(PdfDocumentStore store) {
        this.store = store;
    }

    @GetMapping
    public AppSettings settings() {
        return new AppSettings(store.retentionDays(), store.flag(STRAIGHTEN_ON_OCR, true));
    }

    @PutMapping
    public AppSettings save(@RequestBody AppSettings settings) {
        store.setRetentionDays(settings.retentionDays());
        if (settings.straightenOnOcr() != null) {
            store.setFlag(STRAIGHTEN_ON_OCR, settings.straightenOnOcr());
        }
        return settings();
    }
}
