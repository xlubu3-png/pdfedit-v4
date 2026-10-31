package com.pdfedit.ocr;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads every picture of a folder and writes what was read to {@code java-results.json} there, so the Java
 * engine can be scored against the Python one (RapidOCR) on the very same pixels. Runs only when
 * OCR_PARITY_DIR names such a folder.
 */
@EnabledIfEnvironmentVariable(named = "OCR_PARITY_DIR", matches = ".+")
class OcrParityTest {

    @Test
    void readEveryPicture() throws Exception {
        Path dir = Path.of(System.getenv("OCR_PARITY_DIR"));
        Map<String, Object> results = new LinkedHashMap<>();
        try (OcrEngine engine = OcrEngine.load()) {
            File[] pictures = dir.toFile().listFiles((d, name) -> name.endsWith(".png"));
            for (File picture : pictures) {
                long start = System.nanoTime();
                List<OcrLine> lines = engine.read(ImageIO.read(picture));
                double seconds = (System.nanoTime() - start) / 1e9;
                List<Map<String, Object>> out = new ArrayList<>();
                for (OcrLine line : lines) {
                    out.add(Map.of("text", line.text(), "score", line.score(), "corners", line.corners()));
                }
                results.put(picture.getName(), Map.of("lines", out, "seconds", seconds));
            }
        }
        Files.write(dir.resolve("java-results.json"), JsonMapper.builder().build().writeValueAsBytes(results));
    }
}
