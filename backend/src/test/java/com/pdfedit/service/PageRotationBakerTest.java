package com.pdfedit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pdfedit.text.TextExtraction;

/** A rotated page must look exactly as before, but be an unrotated page the editor can handle. */
class PageRotationBakerTest {

    private static BufferedImage render(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFRenderer(doc).renderImage(0, 1f);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 180, 270})
    void aRotatedPageLooksTheSameAfterwardsAndIsNotRotatedAnymore(int rotation) throws IOException {
        byte[] rotated = TestPdfs.koreanPage(null, rotation);

        byte[] baked = PageRotationBaker.bake(rotated);

        try (PDDocument doc = Loader.loadPDF(baked)) {
            assertThat(doc.getPage(0).getRotation()).isZero();
        }
        BufferedImage before = render(rotated);
        BufferedImage after = render(baked);
        assertThat(after.getWidth()).isEqualTo(before.getWidth());
        assertThat(after.getHeight()).isEqualTo(before.getHeight());
        assertThat(TestPdfs.visiblyDifferentPixels(before, after)).isLessThan(30);
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 180, 270})
    void theTextOfARotatedPageCanBeEditedAfterwards(int rotation) throws IOException {
        byte[] baked = PageRotationBaker.bake(TestPdfs.koreanPage(null, rotation));

        try (PDDocument doc = Loader.loadPDF(baked)) {
            var runs = TextExtraction.extractRuns(doc, 0);
            assertThat(runs).extracting(r -> r.text().replaceAll("\\s", "")).contains("HelloWorld");
            PDRectangle box = doc.getPage(0).getCropBox();
            assertThat(runs).allSatisfy(r -> {
                assertThat(r.x()).isBetween(box.getLowerLeftX() - 1, box.getUpperRightX() + 1);
                assertThat(r.y()).isBetween(box.getLowerLeftY() - 1, box.getUpperRightY() + 1);
            });
        }
    }

    @Test
    void anUnrotatedDocumentIsReturnedUntouched() throws IOException {
        byte[] plain = TestPdfs.koreanPage();

        assertThat(PageRotationBaker.bake(plain)).isSameAs(plain);
    }

    @Test
    void aCropBoxNotAtTheOriginIsHandled() throws IOException {
        byte[] rotated = TestPdfs.koreanPage(new PDRectangle(20, 30, 500, 780), 90);

        byte[] baked = PageRotationBaker.bake(rotated);

        BufferedImage before = render(rotated);
        BufferedImage after = render(baked);
        assertThat(after.getWidth()).isEqualTo(before.getWidth());
        assertThat(TestPdfs.visiblyDifferentPixels(before, after)).isLessThan(30);
    }
}
