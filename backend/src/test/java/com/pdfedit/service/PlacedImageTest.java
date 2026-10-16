package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.pdfedit.controller.ApiExceptionHandler;
import com.pdfedit.controller.ImageController;
import com.pdfedit.controller.TextEditController;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.text.FontMatcher;

import tools.jackson.databind.json.JsonMapper;

/** Pictures placed on a page: uploaded, positioned, drawn into the preview and the export, kept across a restart. */
class PlacedImageTest {

    @TempDir
    Path folder;

    private final TextEditService service = new TextEditService(new FontMatcher());
    private PdfDocumentStore store;
    private MockMvc mvc;
    private String documentId;

    @BeforeEach
    void setUp() throws IOException {
        store = new PdfDocumentStore(folder, Duration.ofDays(7));
        mvc = mvcFor(store);
        documentId = store.store("blank.pdf", TestPdfs.blankPage(), 1);
    }

    private MockMvc mvcFor(PdfDocumentStore forStore) {
        return MockMvcBuilders
                .standaloneSetup(new ImageController(forStore, service), new TextEditController(forStore, service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static byte[] png(Color colour) throws IOException {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                image.setRGB(x, y, colour.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String upload(byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/api/v1/pdf/documents/" + documentId + "/images")
                        .file(new MockMultipartFile("file", "red.png", "image/png", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.width").value(20))
                .andExpect(jsonPath("$.height").value(10))
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).get("imageId").asString();
    }

    private static String box(String imageId, float x, float y, float w, float h, boolean key) {
        return "[{\"id\":\"b1\",\"imageId\":\"" + imageId + "\",\"x\":" + x + ",\"y\":" + y + ",\"width\":" + w
                + ",\"height\":" + h + ",\"keyOutWhite\":" + key + "}]";
    }

    private static int redPixels(BufferedImage image, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                Color c = new Color(image.getRGB(x, y));
                if (c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void aPlacedPictureIsDrawnInThePreviewAtItsPlaceAndSize() throws Exception {
        String imageId = upload(png(Color.RED));
        mvc.perform(put("/api/v1/pdf/documents/" + documentId + "/pages/0/added-images")
                        .contentType(MediaType.APPLICATION_JSON).content(box(imageId, 100, 600, 80, 40, false)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pdf/documents/" + documentId + "/pages/0/text-runs"))
                .andExpect(jsonPath("$.images[0].imageId").value(imageId))
                .andExpect(jsonPath("$.images[0].width").value(80.0));

        byte[] page = service.buildSinglePage(store.getContent(documentId), 0, store.pageEditsSnapshot(documentId, 0));
        BufferedImage image = TestPdfs.render(page); // 2x; the page is 842pt tall
        int top = 2 * (842 - 640);
        int inner = (2 * 80 - 8) * (2 * 40 - 8); // the box less a 4px margin all round
        assertThat(redPixels(image, 2 * 100 + 4, top + 4, 2 * 180 - 4, top + 2 * 40 - 4)).as("the picture is there")
                .isGreaterThan(inner * 95 / 100);
        assertThat(redPixels(image, 0, 0, image.getWidth(), top - 4)).as("nothing above it").isZero();
    }

    @Test
    void thePictureIsInTheExportAndSurvivesARestart() throws Exception {
        String imageId = upload(png(Color.RED));
        mvc.perform(put("/api/v1/pdf/documents/" + documentId + "/pages/0/added-images")
                        .contentType(MediaType.APPLICATION_JSON).content(box(imageId, 50, 50, 100, 50, false)))
                .andExpect(status().isNoContent());

        PdfDocumentStore restarted = new PdfDocumentStore(folder, Duration.ofDays(7));
        byte[] exported = new PdfExportService(restarted, service).export(List.of(new PageSpec(documentId, 0, 0)));

        BufferedImage image = TestPdfs.render(exported);
        int inner = (2 * 100 - 8) * (2 * 50 - 8);
        assertThat(redPixels(image, 2 * 50 + 4, 2 * (842 - 100) + 4, 2 * 150 - 4, 2 * (842 - 50) - 4))
                .as("drawn after a restart")
                .isGreaterThan(inner * 95 / 100);
        mvc = mvcFor(restarted);
        mvc.perform(get("/api/v1/pdf/documents/" + documentId + "/images/" + imageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void aFileThatIsNoPictureIsRefused() throws Exception {
        mvc.perform(multipart("/api/v1/pdf/documents/" + documentId + "/images")
                        .file(new MockMultipartFile("file", "notes.png", "image/png", "not a picture".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBoxWithAPictureThatWasNeverUploadedIsRefused() throws Exception {
        mvc.perform(put("/api/v1/pdf/documents/" + documentId + "/pages/0/added-images")
                        .contentType(MediaType.APPLICATION_JSON).content(box("nope", 10, 10, 50, 50, false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonsenseSizesAreRefused() throws Exception {
        String imageId = upload(png(Color.RED));
        mvc.perform(put("/api/v1/pdf/documents/" + documentId + "/pages/0/added-images")
                        .contentType(MediaType.APPLICATION_JSON).content(box(imageId, 10, 10, 0, 50, false)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePaperOfAPhotographedSignatureCanBeMadeSeeThrough() {
        int paper = 0xFFFAFAFA;
        int ink = 0xFF101040;
        assertThat(ImageDrawing.withoutWhite(paper) >>> 24).as("paper is clear").isZero();
        assertThat(ImageDrawing.withoutWhite(ink)).as("ink is untouched").isEqualTo(ink);
        int grey = 0xFFE0E0E0;
        assertThat(ImageDrawing.withoutWhite(grey) >>> 24).as("a pale grey is half way").isBetween(1, 254);
    }

    @Test
    void aPictureThatIsNeitherPngNorJpegIsKeptAsPng() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        ImageIO.write(image, "bmp", bmp);

        ImageDrawing.Upload upload = ImageDrawing.read(bmp.toByteArray());

        assertThat(upload.contentType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new ByteArrayInputStream(upload.data())).getWidth()).isEqualTo(8);
    }
}
