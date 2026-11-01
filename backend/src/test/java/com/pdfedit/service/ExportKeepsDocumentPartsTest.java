package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

import com.pdfedit.controller.PdfController;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.redact.Redaction;
import com.pdfedit.text.FontMatcher;

/**
 * An export keeps what belongs to its pages beyond their content - the title, bookmarks, form fields, links between
 * them - and brings nothing of the pages left out: they used to come along unseen, through what pointed at them.
 */
class ExportKeepsDocumentPartsTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfExportService exportService = new PdfExportService(store, textEditService);

    /**
     * A three-page plan: "PAGE-n-TEXT" on page n; bookmarks Chapter 1 (with Section 1.1), 2 and 3; a text field on
     * page 1 and a check box on page 2; on page 1 a link to page 3 and a web link that names its page (/P), as Word
     * and Acrobat write them.
     */
    private static byte[] plan(String fieldName) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 760);
                    cs.showText("PAGE-" + i + "-TEXT");
                    cs.endText();
                }
            }
            doc.getDocumentInformation().setTitle("Business plan 2023");
            doc.getDocumentInformation().setAuthor("WINTECH");

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter1 = bookmark("Chapter 1", doc.getPage(0));
            chapter1.addLast(bookmark("Section 1.1", doc.getPage(0)));
            outline.addLast(chapter1);
            outline.addLast(bookmark("Chapter 2", doc.getPage(1)));
            outline.addLast(bookmark("Chapter 3", doc.getPage(2)));

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");
            PDTextField company = new PDTextField(form);
            company.setPartialName(fieldName);
            place(company.getWidgets().get(0), doc.getPage(0), 700);
            form.getFields().add(company);
            company.setValue("WINTECH");
            PDCheckBox agree = new PDCheckBox(form);
            agree.setPartialName("agree");
            place(agree.getWidgets().get(0), doc.getPage(1), 700);
            form.getFields().add(agree);

            PDAnnotationLink toPage3 = new PDAnnotationLink();
            toPage3.setRectangle(new PDRectangle(72, 600, 100, 20));
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(doc.getPage(2));
            toPage3.setDestination(destination);
            doc.getPage(0).getAnnotations().add(toPage3);

            PDAnnotationLink web = new PDAnnotationLink();
            web.setRectangle(new PDRectangle(72, 560, 100, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com");
            web.setAction(uri);
            web.setPage(doc.getPage(0));
            doc.getPage(0).getAnnotations().add(web);

            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDOutlineItem bookmark(String title, PDPage page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(page);
        item.setDestination(destination);
        return item;
    }

    private static void place(PDAnnotationWidget widget, PDPage page, float y) throws IOException {
        widget.setRectangle(new PDRectangle(72, y, 200, 20));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private PDDocument export(List<PageSpec> pages, boolean flatten) throws IOException {
        return Loader.loadPDF(exportService.exportWithReport(pages, flatten).pdf());
    }

    private static List<String> bookmarks(PDDocument doc) throws IOException {
        List<String> found = new ArrayList<>();
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            collect(doc, outline, "", found);
        }
        return found;
    }

    /** Each bookmark as "title -> page number" (1-based), nested ones indented. */
    private static void collect(PDDocument doc, PDOutlineNode node, String indent, List<String> found) throws IOException {
        for (PDOutlineItem item : node.children()) {
            PDPage page = item.findDestinationPage(doc);
            found.add(indent + item.getTitle() + " -> " + (page == null ? "?" : doc.getPages().indexOf(page) + 1));
            collect(doc, item, indent + "  ", found);
        }
    }

    private static List<String> fields(PDDocument doc) {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm(null);
        return form == null ? List.of() : form.getFields().stream().map(PDField::getFullyQualifiedName).toList();
    }

    /** Every byte the file holds, streams decoded: what someone digging through the file would find. */
    private static String everything(PDDocument doc) throws IOException {
        StringBuilder all = new StringBuilder();
        for (COSObjectKey key : doc.getDocument().getXrefTable().keySet()) {
            COSBase object = doc.getDocument().getObjectFromPool(key).getObject();
            if (object instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    all.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
                }
            }
        }
        return all.toString();
    }

    @Test
    void aWholeDocumentKeepsItsTitleBookmarksFormFieldsAndLinks() throws IOException {
        String id = store.store("plan.pdf", plan("company"), 3);

        try (PDDocument out = export(List.of(new PageSpec(id, 0, 0), new PageSpec(id, 1, 0), new PageSpec(id, 2, 0)),
                false)) {
            assertThat(out.getDocumentInformation().getTitle()).isEqualTo("Business plan 2023");
            assertThat(out.getDocumentInformation().getAuthor()).isEqualTo("WINTECH");
            assertThat(bookmarks(out)).containsExactly("Chapter 1 -> 1", "  Section 1.1 -> 1", "Chapter 2 -> 2",
                    "Chapter 3 -> 3");
            assertThat(fields(out)).containsExactly("company", "agree");
            assertThat(out.getDocumentCatalog().getAcroForm(null).getField("company").getValueAsString())
                    .isEqualTo("WINTECH");
            PDAnnotationLink link = (PDAnnotationLink) out.getPage(0).getAnnotations().get(1);
            assertThat(out.getPages().indexOf(((PDPageDestination) link.getDestination()).getPage())).isEqualTo(2);
        }
    }

    @Test
    void anExtractedPageBringsNothingOfTheOtherPages() throws IOException {
        String id = store.store("plan.pdf", plan("company"), 3);

        try (PDDocument out = export(List.of(new PageSpec(id, 0, 0)), false)) {
            String file = everything(out);
            assertThat(file).contains("PAGE-1-TEXT").doesNotContain("PAGE-2-TEXT", "PAGE-3-TEXT");
            assertThat(bookmarks(out)).containsExactly("Chapter 1 -> 1", "  Section 1.1 -> 1");
            assertThat(fields(out)).containsExactly("company");
            // The link to page 3 has nowhere to lead and is gone; the web link stays, naming its new page.
            List<PDAnnotation> annotations = out.getPage(0).getAnnotations();
            assertThat(annotations).hasSize(2);
            assertThat(annotations).filteredOn(a -> a instanceof PDAnnotationLink).singleElement()
                    .satisfies(a -> assertThat(((PDAnnotationLink) a).getAction()).isInstanceOf(PDActionURI.class));
            for (PDAnnotation annotation : annotations) {
                assertThat(annotation.getPage()).isIn(null, out.getPage(0));
            }
        }
    }

    @Test
    void redactedTextIsNowhereInTheFile() throws IOException {
        String id = store.store("plan.pdf", plan("company"), 3);
        store.setRedactions(id, 0, List.of(new Redaction("r", 60, 750, 300, 35)));

        try (PDDocument out = export(List.of(new PageSpec(id, 0, 0)), false)) {
            assertThat(everything(out)).doesNotContain("PAGE-1-TEXT", "PAGE-2-TEXT", "PAGE-3-TEXT");
        }
    }

    @Test
    void reorderedPagesKeepTheirBookmarksAndLinksPointingAtTheirNewPlaces() throws IOException {
        String id = store.store("plan.pdf", plan("company"), 3);

        try (PDDocument out = export(List.of(new PageSpec(id, 2, 0), new PageSpec(id, 0, 0)), false)) {
            // In the order of the original's bookmarks; the pages they lead to as now arranged.
            assertThat(bookmarks(out)).containsExactly("Chapter 1 -> 2", "  Section 1.1 -> 2", "Chapter 3 -> 1");
            PDAnnotationLink link = (PDAnnotationLink) out.getPage(1).getAnnotations().get(1);
            assertThat(out.getPages().indexOf(((PDPageDestination) link.getDestination()).getPage())).isZero();
            assertThat(everything(out)).doesNotContain("PAGE-2-TEXT");
        }
    }

    @Test
    void twoMergedDocumentsKeepBothTheirBookmarksAndFieldsOfTheSameNameStayApart() throws IOException {
        String a = store.store("a.pdf", plan("company"), 3);
        String b = store.store("b.pdf", plan("company"), 3);

        try (PDDocument out = export(List.of(new PageSpec(a, 0, 0), new PageSpec(b, 0, 0)), false)) {
            assertThat(bookmarks(out)).containsExactly("Chapter 1 -> 1", "  Section 1.1 -> 1", "Chapter 1 -> 2",
                    "  Section 1.1 -> 2");
            assertThat(fields(out)).containsExactly("company", "company_2");
        }
    }

    @Test
    void aPictureExportKeepsTheTitleAndBookmarksButHasNoFields() throws IOException {
        String id = store.store("plan.pdf", plan("company"), 3);

        try (PDDocument out = export(List.of(new PageSpec(id, 1, 0), new PageSpec(id, 2, 0)), true)) {
            assertThat(out.getDocumentInformation().getTitle()).isEqualTo("Business plan 2023");
            assertThat(bookmarks(out)).containsExactly("Chapter 2 -> 1", "Chapter 3 -> 2");
            assertThat(fields(out)).isEmpty();
        }
    }

    @Test
    void aDuplicatedPageKeepsItsFieldsButNotTheRestOfTheDocument() throws IOException {
        PdfController controller = new PdfController(store, new PdfThumbnailService(), exportService, textEditService);
        String id = store.store("plan.pdf", plan("company"), 3);

        UploadResponse copy = controller.duplicatePage(id, 0);

        try (PDDocument stored = Loader.loadPDF(store.getContent(copy.documentId()))) {
            assertThat(stored.getNumberOfPages()).isEqualTo(1);
            assertThat(fields(stored)).containsExactly("company");
            assertThat(everything(stored)).doesNotContain("PAGE-2-TEXT", "PAGE-3-TEXT");
        }
    }
}
