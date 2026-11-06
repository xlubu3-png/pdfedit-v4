package com.pdfedit.text;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

/**
 * The straight horizontal and vertical lines drawn on a page - a table's borders - so text copied from a table can
 * be put back into its cells. Lines are found however the program drew them: stroked lines and rectangle outlines,
 * and thin filled rectangles (as Excel and Word draw cell borders). Lines lying on one another are joined into one.
 * Positions are in points from the top-left corner of the page's visible area, as the editor measures its text.
 */
public final class RulingLines {

    /** Thinner than this (points), a filled rectangle is a line, not a shaded box. */
    private static final double THIN = 3;
    /** Shorter than this (points), a line is a tick or a letter's stroke, not a border. */
    private static final double MIN_LENGTH = 4;
    /** Lines whose positions are this close (points) lie on one another. */
    private static final double SAME = 1.5;
    /** Lines whose ends are this close (points) along their length are one line. */
    private static final double JOIN = 2;
    /** A fill lighter than this (0 - 255) is paper (white or nearly); darker is a shaded cell. */
    private static final int PAPER = 240;
    /** A page drawn with more lines than this is a drawing, not a table: no lines are given. */
    private static final int MAX_LINES = 20_000;

    /** A horizontal line at {@code y}, from {@code x1} to {@code x2}. */
    public record Horizontal(double y, double x1, double x2) {
    }

    /** A vertical line at {@code x}, from {@code y1} (top) to {@code y2}. */
    public record Vertical(double x, double y1, double y2) {
    }

    /**
     * @param turn   degrees the lines were found turned by (a scan straightened first: the page turned clockwise
     *               about ({@code pivotX}, {@code pivotY}), points from the top-left); 0 when not turned. Text
     *               placed on the page is turned the same way to be laid against them.
     */
    public record Lines(List<Horizontal> horizontal, List<Vertical> vertical, double turn, double pivotX,
                        double pivotY) {
        public static final Lines NONE = new Lines(List.of(), List.of());

        public Lines(List<Horizontal> horizontal, List<Vertical> vertical) {
            this(horizontal, vertical, 0, 0, 0);
        }
    }

    private RulingLines() {
    }

    public static Lines of(PDPage page) throws IOException {
        Finder finder = new Finder(page);
        try {
            finder.processPage(page);
        } catch (TooMany e) {
            return Lines.NONE;
        }
        PDRectangle box = page.getCropBox();
        List<Horizontal> horizontal = new ArrayList<>();
        List<Vertical> vertical = new ArrayList<>();
        // From PDF space (y up) to the page's top-left corner (y down).
        for (double[] s : finder.horizontal) {
            horizontal.add(new Horizontal(box.getUpperRightY() - s[0], s[1] - box.getLowerLeftX(), s[2] - box.getLowerLeftX()));
        }
        for (double[] s : finder.vertical) {
            double top = box.getUpperRightY() - s[2];
            double bottom = box.getUpperRightY() - s[1];
            vertical.add(new Vertical(s[0] - box.getLowerLeftX(), top, bottom));
        }
        return new Lines(joinHorizontal(horizontal), joinVertical(vertical));
    }

    private static List<Horizontal> joinHorizontal(List<Horizontal> lines) {
        List<Horizontal> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(Horizontal::y).thenComparingDouble(Horizontal::x1));
        List<Horizontal> out = new ArrayList<>();
        for (Horizontal line : sorted) {
            Horizontal joined = null;
            for (int i = out.size() - 1; i >= 0 && out.get(i).y() >= line.y() - SAME; i--) {
                Horizontal o = out.get(i);
                if (line.x1() <= o.x2() + JOIN && line.x2() >= o.x1() - JOIN) {
                    joined = new Horizontal((o.y() + line.y()) / 2, Math.min(o.x1(), line.x1()), Math.max(o.x2(), line.x2()));
                    out.set(i, joined);
                    break;
                }
            }
            if (joined == null) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<Vertical> joinVertical(List<Vertical> lines) {
        List<Vertical> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(Vertical::x).thenComparingDouble(Vertical::y1));
        List<Vertical> out = new ArrayList<>();
        for (Vertical line : sorted) {
            Vertical joined = null;
            for (int i = out.size() - 1; i >= 0 && out.get(i).x() >= line.x() - SAME; i--) {
                Vertical o = out.get(i);
                if (line.y1() <= o.y2() + JOIN && line.y2() >= o.y1() - JOIN) {
                    joined = new Vertical((o.x() + line.x()) / 2, Math.min(o.y1(), line.y1()), Math.max(o.y2(), line.y2()));
                    out.set(i, joined);
                    break;
                }
            }
            if (joined == null) {
                out.add(line);
            }
        }
        return out;
    }

    private static final class TooMany extends RuntimeException {
        TooMany() {
            super(null, null, false, false);
        }
    }

    /** Collects the lines of the paths painted on the page, in PDF space: {y, x1, x2} and {x, y1, y2} (y up). */
    private static final class Finder extends PDFGraphicsStreamEngine {
        final List<double[]> horizontal = new ArrayList<>();
        final List<double[]> vertical = new ArrayList<>();
        /** The path being built: its straight segments, and its rectangles. */
        private final List<Point2D[]> segments = new ArrayList<>();
        private final List<Point2D[]> rectangles = new ArrayList<>();
        /** Points of the current subpath, to see a rectangle drawn with moveto/lineto. */
        private final List<Point2D> subpath = new ArrayList<>();
        private Point2D current;
        private Point2D start;

        Finder(PDPage page) {
            super(page);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            rectangles.add(new Point2D[] {p0, p1, p2, p3});
            current = p0;
            start = p0;
            subpath.clear();
        }

        @Override
        public void moveTo(float x, float y) {
            finishSubpath();
            current = new Point2D.Float(x, y);
            start = current;
            subpath.clear();
            subpath.add(current);
        }

        @Override
        public void lineTo(float x, float y) {
            Point2D to = new Point2D.Float(x, y);
            if (current != null) {
                segments.add(new Point2D[] {current, to});
            }
            current = to;
            subpath.add(to);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);
            subpath.clear();
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void closePath() {
            if (current != null && start != null) {
                segments.add(new Point2D[] {current, start});
            }
            finishSubpath();
            current = start;
        }

        /** Four corners (and back to the first): a rectangle drawn point by point, as some programs do. */
        private void finishSubpath() {
            if (subpath.size() == 4 || subpath.size() == 5) {
                Point2D[] corners = subpath.subList(0, 4).toArray(new Point2D[0]);
                if (axisAligned(corners)) {
                    rectangles.add(corners);
                }
            }
            subpath.clear();
        }

        @Override
        public void endPath() {
            clear();
        }

        @Override
        public void strokePath() {
            subpath.clear();
            for (Point2D[] r : rectangles) {
                for (int i = 0; i < 4; i++) {
                    addLine(r[i], r[(i + 1) % 4]);
                }
            }
            for (Point2D[] s : segments) {
                addLine(s[0], s[1]);
            }
            clear();
        }

        @Override
        public void fillPath(int windingRule) {
            finishSubpath();
            // A thin filled rectangle is a line (a cell border). A wider one in a colour is a shaded cell (a grey
            // header row), whose edges part it from the cells beside it even with no line drawn; a white one, or one
            // as big as the page, is the paper.
            boolean shaded = shaded();
            for (Point2D[] r : rectangles) {
                if (!addThinRectangle(r) && shaded && !pageSized(r)) {
                    for (int i = 0; i < 4; i++) {
                        addLine(r[i], r[(i + 1) % 4]);
                    }
                }
            }
            clear();
        }

        /** Whether the fill colour is darker than paper (a shade, not white). */
        private boolean shaded() {
            try {
                int rgb = getGraphicsState().getNonStrokingColor().toRGB();
                int luma = (((rgb >> 16) & 0xff) * 299 + ((rgb >> 8) & 0xff) * 587 + (rgb & 0xff) * 114) / 1000;
                return luma < PAPER;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        /** Whether the rectangle covers most of the page: a background, not a cell. */
        private boolean pageSized(Point2D[] r) {
            PDRectangle box = getPage().getCropBox();
            double width = Math.abs(r[2].getX() - r[0].getX());
            double height = Math.abs(r[2].getY() - r[0].getY());
            return width * height >= 0.6 * box.getWidth() * box.getHeight();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            strokePath();
        }

        @Override
        public void shadingFill(COSName shadingName) {
        }

        @Override
        public void drawImage(PDImage pdImage) {
        }

        @Override
        public void clip(int windingRule) {
        }

        private void clear() {
            segments.clear();
            rectangles.clear();
            subpath.clear();
        }

        /** Adds the rectangle as a line when it is a thin one; whether it was. */
        private boolean addThinRectangle(Point2D[] r) {
            double minX = Math.min(Math.min(r[0].getX(), r[1].getX()), Math.min(r[2].getX(), r[3].getX()));
            double maxX = Math.max(Math.max(r[0].getX(), r[1].getX()), Math.max(r[2].getX(), r[3].getX()));
            double minY = Math.min(Math.min(r[0].getY(), r[1].getY()), Math.min(r[2].getY(), r[3].getY()));
            double maxY = Math.max(Math.max(r[0].getY(), r[1].getY()), Math.max(r[2].getY(), r[3].getY()));
            if (!axisAligned(r)) {
                return false;
            }
            double width = maxX - minX;
            double height = maxY - minY;
            if (height <= THIN && width >= MIN_LENGTH) {
                add(horizontal, new double[] {(minY + maxY) / 2, minX, maxX});
                return true;
            } else if (width <= THIN && height >= MIN_LENGTH) {
                add(vertical, new double[] {(minX + maxX) / 2, minY, maxY});
                return true;
            }
            return width <= THIN || height <= THIN;
        }

        private static boolean axisAligned(Point2D[] r) {
            for (int i = 0; i < 4; i++) {
                Point2D a = r[i];
                Point2D b = r[(i + 1) % 4];
                if (Math.abs(a.getX() - b.getX()) > 0.5 && Math.abs(a.getY() - b.getY()) > 0.5) {
                    return false;
                }
            }
            return true;
        }

        private void addLine(Point2D a, Point2D b) {
            double dx = Math.abs(a.getX() - b.getX());
            double dy = Math.abs(a.getY() - b.getY());
            if (dy <= 0.5 && dx >= MIN_LENGTH) {
                add(horizontal, new double[] {(a.getY() + b.getY()) / 2, Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX())});
            } else if (dx <= 0.5 && dy >= MIN_LENGTH) {
                add(vertical, new double[] {(a.getX() + b.getX()) / 2, Math.min(a.getY(), b.getY()), Math.max(a.getY(), b.getY())});
            }
        }

        private void add(List<double[]> list, double[] line) {
            if (horizontal.size() + vertical.size() >= MAX_LINES) {
                throw new TooMany();
            }
            list.add(line);
        }
    }
}
