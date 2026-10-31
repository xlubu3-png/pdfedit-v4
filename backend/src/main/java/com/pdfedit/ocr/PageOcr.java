package com.pdfedit.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import com.pdfedit.dto.TextRunDto;
import com.pdfedit.service.PageEdits;
import com.pdfedit.service.TextEditService;

import ai.onnxruntime.OrtException;

/**
 * Recognizes the text of a page that is a picture (a scan): the page is drawn at {@value #DPI} dpi and read by
 * the {@link OcrEngine}, and every line found is placed back on the page in points. Lines where the page
 * already has real text are left out, so a page that is partly text, partly picture gets only the picture's
 * words.
 */
@Service
public class PageOcr implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PageOcr.class);

    /** Enough for print down to 8 pt: the models read text from about 15 pixels high. */
    static final float DPI = 200f;
    /** How far (points) around a real text line an OCR line still counts as the same text. */
    private static final float SAME_TEXT_MARGIN = 2f;

    private final TextEditService textEditService;
    private OcrEngine engine;

    public PageOcr(TextEditService textEditService) {
        this.textEditService = textEditService;
    }

    /**
     * @param lines       the recognized lines, top to bottom
     * @param alreadyText lines left out because the page has real text there
     * @param seconds     how long the recognition took
     */
    public record Result(List<OcrText> lines, int alreadyText, double seconds) {
    }

    /** @throws OcrUnavailableException when the recognition engine cannot run on this computer */
    public Result recognize(byte[] pdf, int pageIndex) throws IOException {
        long start = System.nanoTime();
        BufferedImage picture;
        PDRectangle crop;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            crop = doc.getPage(pageIndex).getCropBox();
            picture = new PDFRenderer(doc).renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
        }
        List<TextRunDto> runs = textEditService.describePage(pdf, pageIndex, PageEdits.NONE).runs();
        List<OcrLine> read;
        try {
            read = engine().read(picture);
        } catch (OrtException e) {
            throw new OcrUnavailableException(e);
        }
        float k = 72f / DPI;
        List<OcrText> lines = new ArrayList<>();
        int alreadyText = 0;
        for (OcrLine line : read) {
            float x = crop.getLowerLeftX() + line.left() * k;
            float top = crop.getUpperRightY() - line.top() * k;
            float bottom = crop.getUpperRightY() - line.bottom() * k;
            List<Float> centers = new ArrayList<>(line.centers().length);
            for (float c : line.centers()) {
                centers.add(Math.round(c * 1000) / 1000f);
            }
            OcrText text = new OcrText(line.text(), x, bottom, (line.right() - line.left()) * k, top - bottom,
                    line.score(), centers, line.orientation());
            if (onRealText(text, runs)) {
                alreadyText++;
            } else {
                lines.add(text);
            }
        }
        return new Result(lines, alreadyText, (System.nanoTime() - start) / 1e9);
    }

    private static boolean onRealText(OcrText line, List<TextRunDto> runs) {
        float cx = line.x() + line.width() / 2;
        float cy = line.y() + line.height() / 2;
        for (TextRunDto run : runs) {
            if (cx >= run.x() - SAME_TEXT_MARGIN && cx <= run.x() + run.width() + SAME_TEXT_MARGIN
                    && cy >= run.boxBottom() - SAME_TEXT_MARGIN && cy <= run.boxTop() + SAME_TEXT_MARGIN) {
                return true;
            }
        }
        return false;
    }

    /** Loaded on first use: the models take a second or two and some memory, and most documents never need them. */
    private synchronized OcrEngine engine() {
        if (engine == null) {
            try {
                engine = OcrEngine.load();
            } catch (IOException | OrtException | RuntimeException | UnsatisfiedLinkError e) {
                log.error("The OCR engine could not be started", e);
                throw new OcrUnavailableException(e);
            }
        }
        return engine;
    }

    @Override
    public synchronized void destroy() throws OrtException {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
