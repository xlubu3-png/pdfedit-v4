package com.pdfedit.compare;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.pdfedit.text.TextLines;

/**
 * Where two pages of the same size look different: both are drawn in grey at a low resolution, compared in
 * small cells, and the changed cells that touch are joined into areas. This shows what text comparison
 * cannot: a picture, a line, a stamp or a colour that changed.
 */
public final class VisualDiff {

    static final float DPI = 60f;
    /** Cell side in pixels at {@link #DPI}: about 2.4 mm. */
    private static final int CELL = 6;
    /** A pixel counts as changed when its grey differs by more than this (0-255): not just anti-aliasing. */
    private static final int THRESHOLD = 48;
    /** A cell counts as changed when this many of its pixels are. */
    private static final int PIXELS = 3;
    private static final int MAX_AREAS = 200;

    private VisualDiff() {
    }

    /** The areas (user space of page {@code pa} of {@code a}) that differ; null when the pages differ in size. */
    public static List<TextLines.Area> areas(PDDocument a, int pa, PDDocument b, int pb) throws IOException {
        PDRectangle cropA = a.getPage(pa).getCropBox();
        PDRectangle cropB = b.getPage(pb).getCropBox();
        if (Math.abs(cropA.getWidth() - cropB.getWidth()) > 1 || Math.abs(cropA.getHeight() - cropB.getHeight()) > 1) {
            return null;
        }
        BufferedImage imageA = new PDFRenderer(a).renderImageWithDPI(pa, DPI, ImageType.GRAY);
        BufferedImage imageB = new PDFRenderer(b).renderImageWithDPI(pb, DPI, ImageType.GRAY);
        int width = Math.min(imageA.getWidth(), imageB.getWidth());
        int height = Math.min(imageA.getHeight(), imageB.getHeight());
        int columns = (width + CELL - 1) / CELL;
        int rows = (height + CELL - 1) / CELL;
        boolean[][] changed = new boolean[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int count = 0;
                for (int y = row * CELL; y < Math.min(height, (row + 1) * CELL) && count < PIXELS; y++) {
                    for (int x = column * CELL; x < Math.min(width, (column + 1) * CELL); x++) {
                        int ga = imageA.getRaster().getSample(x, y, 0);
                        int gb = imageB.getRaster().getSample(x, y, 0);
                        if (Math.abs(ga - gb) > THRESHOLD && ++count >= PIXELS) {
                            break;
                        }
                    }
                }
                changed[row][column] = count >= PIXELS;
            }
        }
        float scale = 72f / DPI;
        List<TextLines.Area> areas = new ArrayList<>();
        for (int[] cells : groups(changed)) {
            if (areas.size() >= MAX_AREAS) {
                break;
            }
            float left = cells[1] * CELL * scale;
            float top = cells[0] * CELL * scale;
            float right = Math.min(width, (cells[3] + 1) * CELL) * scale;
            float bottom = Math.min(height, (cells[2] + 1) * CELL) * scale;
            areas.add(new TextLines.Area(cropA.getLowerLeftX() + left, cropA.getUpperRightY() - bottom,
                    right - left, bottom - top));
        }
        return areas;
    }

    /** Changed cells that touch (counting a one-cell gap as touching), as {top row, left column, bottom, right}. */
    private static List<int[]> groups(boolean[][] changed) {
        int rows = changed.length;
        int columns = rows == 0 ? 0 : changed[0].length;
        boolean[][] seen = new boolean[rows][columns];
        List<int[]> groups = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!changed[row][column] || seen[row][column]) {
                    continue;
                }
                int[] box = {row, column, row, column};
                Deque<int[]> todo = new ArrayDeque<>();
                todo.push(new int[] {row, column});
                seen[row][column] = true;
                while (!todo.isEmpty()) {
                    int[] cell = todo.pop();
                    box[0] = Math.min(box[0], cell[0]);
                    box[1] = Math.min(box[1], cell[1]);
                    box[2] = Math.max(box[2], cell[0]);
                    box[3] = Math.max(box[3], cell[1]);
                    for (int dr = -2; dr <= 2; dr++) {
                        for (int dc = -2; dc <= 2; dc++) {
                            int r = cell[0] + dr;
                            int c = cell[1] + dc;
                            if (r >= 0 && r < rows && c >= 0 && c < columns && changed[r][c] && !seen[r][c]) {
                                seen[r][c] = true;
                                todo.push(new int[] {r, c});
                            }
                        }
                    }
                }
                groups.add(box);
            }
        }
        return groups;
    }
}
