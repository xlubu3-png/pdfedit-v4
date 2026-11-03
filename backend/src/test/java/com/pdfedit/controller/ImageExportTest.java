package com.pdfedit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.pdfedit.dto.ImageExportRequest;
import com.pdfedit.dto.PageSpec;
import com.pdfedit.dto.UploadResponse;
import com.pdfedit.redact.Redaction;
import com.pdfedit.service.InvalidEditException;
import com.pdfedit.service.PdfDocumentStore;
import com.pdfedit.service.PdfExportService;
import com.pdfedit.service.PdfThumbnailService;
import com.pdfedit.service.TextEditService;
import com.pdfedit.text.FontMatcher;

/** 사진으로 내보내기: the pages as exported, as pictures. */
class ImageExportTest {

    private final PdfDocumentStore store = new PdfDocumentStore();
    private final TextEditService textEditService = new TextEditService(new FontMatcher());
    private final PdfController controller = new PdfController(
            store, new PdfThumbnailService(), new PdfExportService(store, textEditService), textEditService);

    private record Picture(String name, BufferedImage image) {
    }

    private static List<Picture> unzip(byte[] zip) throws IOException {
        List<Picture> pictures = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                pictures.add(new Picture(entry.getName(), ImageIO.read(new ByteArrayInputStream(in.readAllBytes()))));
            }
        }
        return pictures;
    }

    @Test
    void onePageComesAsOneJpgOfTheAskedSharpness() throws IOException {
        UploadResponse blank = controller.createBlankDocument(144, 72);

        ResponseEntity<byte[]> response = controller.exportImages(new ImageExportRequest(
                List.of(new PageSpec(blank.documentId(), 0, 0)), "계약서_수정본", null, 200f));

        assertThat(response.getHeaders().getContentType()).hasToString("image/jpeg");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("계약서_수정본.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        // 2 x 1 inches at 200 DPI
        assertThat(image.getWidth()).isEqualTo(400);
        assertThat(image.getHeight()).isEqualTo(200);
    }

    @Test
    void severalPagesComeAsAZipOfNumberedPicturesInTheListsOrderAndTurn() throws IOException {
        UploadResponse wide = controller.createBlankDocument(144, 72);
        UploadResponse tall = controller.createBlankDocument(72, 144);

        ResponseEntity<byte[]> response = controller.exportImages(new ImageExportRequest(List.of(
                new PageSpec(tall.documentId(), 0, 0), new PageSpec(wide.documentId(), 0, 90),
                new PageSpec(wide.documentId(), 0, 0)), "보고서.pdf", "PNG", 72f));

        assertThat(response.getHeaders().getContentType()).hasToString("application/zip");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("보고서.zip");
        List<Picture> pictures = unzip(response.getBody());
        assertThat(pictures).extracting(Picture::name).containsExactly("보고서_01.png", "보고서_02.png", "보고서_03.png");
        assertThat(pictures).extracting(p -> p.image().getWidth() + "x" + p.image().getHeight())
                .containsExactly("72x144", "72x144", "144x72");
    }

    @Test
    void aRedactedAreaIsBlackInThePicture() throws IOException {
        UploadResponse blank = controller.createBlankDocument(144, 144);
        store.setRedactions(blank.documentId(), 0, List.of(new Redaction("a", 0, 0, 72, 144)));

        ResponseEntity<byte[]> response = controller.exportImages(new ImageExportRequest(
                List.of(new PageSpec(blank.documentId(), 0, 0)), "a", "png", 72f));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.getBody()));
        assertThat(image.getRGB(20, 72) & 0xffffff).as("inside the area").isEqualTo(0x000000);
        assertThat(image.getRGB(120, 72) & 0xffffff).as("outside it").isEqualTo(0xffffff);
        assertThat(response.getHeaders().getFirst(PdfController.REDACTION_HEADER)).startsWith("areas=1;");
    }

    @Test
    void theSharpnessIsKeptWithinBoundsAndTheNameCannotMakeFolders() throws IOException {
        UploadResponse blank = controller.createBlankDocument(72, 72);
        List<PageSpec> twice = List.of(new PageSpec(blank.documentId(), 0, 0), new PageSpec(blank.documentId(), 0, 0));

        List<Picture> sharp = unzip(controller.exportImages(
                new ImageExportRequest(twice, "../a/b:c", "jpg", 100_000f)).getBody());
        BufferedImage blurred = ImageIO.read(new ByteArrayInputStream(controller.exportImages(new ImageExportRequest(
                List.of(twice.get(0)), "", "jpg", 1f)).getBody()));

        assertThat(sharp).extracting(Picture::name).containsExactly(".._a_b_c_01.jpg", ".._a_b_c_02.jpg");
        assertThat(sharp.get(0).image().getWidth()).isEqualTo(600);
        assertThat(blurred.getWidth()).isEqualTo(72);
    }

    @Test
    void exportingNoPagesIsRefused() {
        assertThatThrownBy(() -> controller.exportImages(new ImageExportRequest(List.of(), "a", null, null)))
                .isInstanceOf(InvalidEditException.class);
    }
}
