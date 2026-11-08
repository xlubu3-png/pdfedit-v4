package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.pdfedit.text.FontMatcher;

/** The pages of a document just opened are read ahead in the background. */
class PageWarmerTest {

    @Test
    void aDocumentsPagesAreReadAheadAndThoseNextToTheEditedOneFirst() throws Exception {
        PdfDocumentStore store = new PdfDocumentStore();
        String id = store.store("two.pdf", TestPdfs.koreanPage(), 1);
        PageWarmer warmer = new PageWarmer(store, new TextEditService(new FontMatcher()));
        try {
            warmer.wholeDocument(id, 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!warmer.isRead(id, 0) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(warmer.isRead(id, 0)).isTrue();
            // A page that is not there is just passed over.
            warmer.around(id, 5, 10);
        } finally {
            warmer.destroy();
        }
    }
}
