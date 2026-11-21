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

    /** A document of `pages` pages, each with a line of text. */
    private static byte[] pages(int pages) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.font.PDFont font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < pages; i++) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void onlyTheFirstPagesOfADocumentJustOpenedAreReadAheadNotAllOfThem() throws Exception {
        PdfDocumentStore store = new PdfDocumentStore();
        int count = PageWarmer.FIRST_PAGES + 6;
        String id = store.store("many.pdf", pages(count), count);
        PageWarmer warmer = new PageWarmer(store, new TextEditService(new FontMatcher()));
        try {
            warmer.wholeDocument(id, count);
            int last = PageWarmer.FIRST_PAGES - 1;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!warmer.isRead(id, last) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            Thread.sleep(500);
            for (int p = 0; p < count; p++) {
                assertThat(warmer.isRead(id, p)).as("page %d", p + 1).isEqualTo(p < PageWarmer.FIRST_PAGES);
            }
            // The pages around one being edited are still read ahead.
            warmer.around(id, count - 2, count);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!warmer.isRead(id, count - 1) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(warmer.isRead(id, count - 1)).isTrue();
            assertThat(warmer.isRead(id, count - 3)).isTrue();
        } finally {
            warmer.destroy();
        }
    }
}
