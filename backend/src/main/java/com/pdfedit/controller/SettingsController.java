package com.pdfedit.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    static final String AUTO_OCR = "autoOcr";

    private final PdfDocumentStore store;
    /** Whether scans are recognized as they are opened until the user says otherwise (off for the browser tests). */
    private final boolean autoOcrUnlessSet;

    public SettingsController(PdfDocumentStore store) {
        this(store, true);
    }

    @Autowired
    public SettingsController(PdfDocumentStore store,
            @Value("${app.auto-ocr:true}") boolean autoOcrUnlessSet) {
        this.store = store;
        this.autoOcrUnlessSet = autoOcrUnlessSet;
    }

    @GetMapping
    public AppSettings settings() {
        return new AppSettings(store.retentionDays(), store.flag(STRAIGHTEN_ON_OCR, true),
                store.flag(AUTO_OCR, autoOcrUnlessSet));
    }

    @PutMapping
    public AppSettings save(@RequestBody AppSettings settings) {
        store.setRetentionDays(settings.retentionDays());
        if (settings.straightenOnOcr() != null) {
            store.setFlag(STRAIGHTEN_ON_OCR, settings.straightenOnOcr());
        }
        if (settings.autoOcr() != null) {
            store.setFlag(AUTO_OCR, settings.autoOcr());
        }
        return settings();
    }
}
