package com.pdfedit.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.TestPdfs;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

class TextEditControllerTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private MockMvc mvc;
    private String documentId;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders
                .standaloneSetup(new TextEditController(store, new TextEditService(new FontMatcher())))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        documentId = store.store("korean.pdf", TestPdfs.koreanPage(), 1);
    }

    @Test
    void textRunsListsTheLinesOfThePage() throws Exception {
        mvc.perform(get(base() + "/text-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rotated").value(false))
                .andExpect(jsonPath("$.runs[1].text", startsWith("Hello")))
                .andExpect(jsonPath("$.runs[1].edited").value(false));
    }

    @Test
    void savedEditsAreReportedAndCanBeResetAndShowUpInThePreview() throws Exception {
        mvc.perform(put(base() + "/text-edits").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"index\":1,\"text\":\"Hold World\"}]"))
                .andExpect(status().isNoContent());

        mvc.perform(get(base() + "/text-runs"))
                .andExpect(jsonPath("$.runs[1].currentText").value("Hold World"))
                .andExpect(jsonPath("$.runs[1].edited").value(true));
        mvc.perform(get(base() + "/preview"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        mvc.perform(delete(base() + "/text-edits")).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/text-runs"))
                .andExpect(jsonPath("$.runs[1].currentText", startsWith("Hello")))
                .andExpect(jsonPath("$.runs[1].edited").value(false));
    }

    @Test
    void restyledAndMovedLinesAndAddedTextsRoundTripThroughTheApi() throws Exception {
        mvc.perform(put(base() + "/text-edits").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"index\":1,\"text\":\"Hello World\",\"fontSize\":18,\"bold\":true,"
                        + "\"color\":\"#336699\",\"dx\":10.5,\"dy\":-4}]"))
                .andExpect(status().isNoContent());
        mvc.perform(put(base() + "/added-texts").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"a1\",\"text\":\"Added\",\"x\":100,\"y\":400,\"fontFamily\":null,"
                        + "\"fontSize\":14,\"bold\":false,\"color\":\"#ff0000\"}]"))
                .andExpect(status().isNoContent());

        mvc.perform(get(base() + "/text-runs"))
                .andExpect(jsonPath("$.runs[1].sourceFont").isNotEmpty())
                .andExpect(jsonPath("$.runs[1].color").value(startsWith("#")))
                .andExpect(jsonPath("$.runs[1].editFontSize").value(18.0))
                .andExpect(jsonPath("$.runs[1].editBold").value(true))
                .andExpect(jsonPath("$.runs[1].editColor").value("#336699"))
                .andExpect(jsonPath("$.runs[1].dx").value(10.5))
                .andExpect(jsonPath("$.runs[1].dy").value(-4.0))
                .andExpect(jsonPath("$.runs[0].editFontSize").doesNotExist())
                .andExpect(jsonPath("$.added[0].text").value("Added"))
                .andExpect(jsonPath("$.added[0].fontSize").value(14.0));
        mvc.perform(get(base() + "/preview")).andExpect(status().isOk());

        mvc.perform(delete(base() + "/text-edits")).andExpect(status().isNoContent());
        mvc.perform(get(base() + "/text-runs"))
                .andExpect(jsonPath("$.added").isEmpty())
                .andExpect(jsonPath("$.runs[1].edited").value(false));
    }

    @Test
    void nonsenseStylesAreABadRequest() throws Exception {
        mvc.perform(put(base() + "/text-edits").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"index\":1,\"text\":\"x\",\"fontSize\":0}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(base() + "/added-texts").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"a\",\"text\":\"x\",\"x\":1,\"y\":1,\"fontSize\":12,\"bold\":false,\"color\":\"red\"}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addingTextToARotatedPageIsAConflict() throws Exception {
        String rotatedId = store.store("rotated.pdf", rotatedPdf(), 1);

        mvc.perform(put("/api/v1/pdf/documents/" + rotatedId + "/pages/0/added-texts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"a\",\"text\":\"x\",\"x\":1,\"y\":1,\"fontSize\":12,\"bold\":false}]"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownDocumentIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/pdf/documents/nope/pages/0/text-runs")).andExpect(status().isNotFound());
    }

    @Test
    void unknownPageIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/pdf/documents/" + documentId + "/pages/7/text-runs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void savingEditsOnARotatedPageIsAConflict() throws Exception {
        String rotatedId = store.store("rotated.pdf", rotatedPdf(), 1);

        mvc.perform(put("/api/v1/pdf/documents/" + rotatedId + "/pages/0/text-edits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"index\":0,\"text\":\"x\"}]"))
                .andExpect(status().isConflict());
    }

    private String base() {
        return "/api/v1/pdf/documents/" + documentId + "/pages/0";
    }

    private static byte[] rotatedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            page.setRotation(90);
            document.addPage(page);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
