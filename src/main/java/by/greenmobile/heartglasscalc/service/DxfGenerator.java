package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DxfGenerator {

    public String generateDxf(GlassParameters params) {
        if (params == null) {
            log.warn("generateDxf(): params = null");
            return "";
        }
        if (params.isHoneycomb()) {
            return generateHoneycombDxf(params);
        } else {
            return generateZigzagDxf(params);
        }
    }

    // ========================================================================
    // ЗИГЗАГ (как было)
    // ========================================================================

    private String generateZigzagDxf(GlassParameters params) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double spacing = params.getLineSpacing();
        int lineCount = params.getLineCount() != null ? params.getLineCount() : 0;
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double safeTop = offset;
        double safeBottom = height - offset;
        double safeLeft = offset;
        double safeRight = width - offset;

        writeHeader(out);

        addRectangle(out, 0, 0, width, height, "GLASS");

        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            addRectangle(out, offset, offset, width - 2 * offset, height - 2 * offset, "SAFE_ZONE");
        }

        if (verticalBusbars) {
            addRectangle(out, safeLeft, safeTop, width - 2 * offset, busbarWidth, "BUSBAR");
            addRectangle(out, safeLeft, safeBottom - busbarWidth, width - 2 * offset, busbarWidth, "BUSBAR");
        } else {
            addRectangle(out, safeLeft, safeTop, busbarWidth, safeBottom - safeTop, "BUSBAR");
            addRectangle(out, safeRight - busbarWidth, safeTop, busbarWidth, safeBottom - safeTop, "BUSBAR");
        }

        if (lineCount > 0 && spacing > 0) {
            if (verticalBusbars) {
                double y1 = safeTop + busbarWidth;
                double y2 = safeBottom - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double x = offset + i * spacing;
                    if (x < safeLeft || x > safeRight) continue;
                    addLine(out, x, y1, x, y2, "ABLATION");
                }
            } else {
                double x1 = safeLeft + busbarWidth;
                double x2 = safeRight - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double y = offset + i * spacing;
                    if (y < safeTop || y > safeBottom) continue;
                    addLine(out, x1, y, x2, y, "ABLATION");
                }
            }
        }

        writeFooter(out);
        out.flush();
        return sw.toString();
    }

    // ========================================================================
    // СОТЫ (подрезка полигонов и закрытый контур)
    // ========================================================================

    private String generateHoneycombDxf(GlassParameters params) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide() != null ? params.getHexSide() : 30.0;
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        double clearance = (params.getBusbarClearanceMm() != null && params.getBusbarClearanceMm() >= 0)
                ? params.getBusbarClearanceMm()
                : gap;

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        double safeLeft = offset;
        double safeRight = width - offset;
        double safeTop = offset;
        double safeBottom = height - offset;

        double clipMinX, clipMaxX, clipMinY, clipMaxY;
        if (verticalBusbars) {
            clipMinX = safeLeft;
            clipMaxX = safeRight;
            clipMinY = safeTop + busbarWidth + clearance;
            clipMaxY = safeBottom - busbarWidth - clearance;
        } else {
            clipMinX = safeLeft + busbarWidth + clearance;
            clipMaxX = safeRight - busbarWidth - clearance;
            clipMinY = safeTop;
            clipMaxY = safeBottom;
        }

        writeHeader(out);

        addRectangle(out, 0, 0, width, height, "GLASS");
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            addRectangle(out, offset, offset, width - 2 * offset, height - 2 * offset, "SAFE_ZONE");
        }

        if (verticalBusbars) {
            addRectangle(out, safeLeft, safeTop, width - 2 * offset, busbarWidth, "BUSBAR");
            addRectangle(out, safeLeft, safeBottom - busbarWidth, width - 2 * offset, busbarWidth, "BUSBAR");
        } else {
            addRectangle(out, safeLeft, safeTop, busbarWidth, safeBottom - safeTop, "BUSBAR");
            addRectangle(out, safeRight - busbarWidth, safeTop, busbarWidth, safeBottom - safeTop, "BUSBAR");
        }

        if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) {
            log.warn("DXF соты: рабочая зона выродилась (clearance={} мм).", clearance);
            writeFooter(out);
            out.flush();
            return sw.toString();
        }

        int colMin = (int) Math.floor((clipMinX - 2 * a) / stepX) - 2;
        int colMax = (int) Math.ceil((clipMaxX + 2 * a) / stepX) + 2;
        int rowMin = (int) Math.floor((clipMinY - 2 * hexHeight) / stepY) - 3;
        int rowMax = (int) Math.ceil((clipMaxY + 2 * hexHeight) / stepY) + 3;

        int drawn = 0;
        int clippedCount = 0;

        for (int col = colMin; col <= colMax; col++) {
            double cx = (col * stepX) + safeLeft + a;
            double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

            for (int row = rowMin; row <= rowMax; row++) {
                double cy = safeTop + (hexHeight / 2.0) + (row * stepY) + colOffsetY;

                // Быстрый bbox-фильтр
                if (cx + a < clipMinX - 1) continue;
                if (cx - a > clipMaxX + 1) continue;
                if (cy + hexHeight / 2.0 < clipMinY - 1) continue;
                if (cy - hexHeight / 2.0 > clipMaxY + 1) continue;

                List<Point> hex = buildHexagon(cx, cy, a);
                List<Point> clipped = clipPolygonToRect(hex, clipMinX, clipMaxX, clipMinY, clipMaxY);
                if (clipped.size() < 3) continue;

                if (clipped.size() != hex.size()) clippedCount++;

                addLwPolylineClosed(out, clipped, "ABLATION");
                drawn++;
            }
        }

        writeFooter(out);
        out.flush();

        log.info("DXF соты: контуров={} (подрезанных={}), a={} gap={} clearance={}",
                drawn, clippedCount, a, gap, clearance);

        return sw.toString();
    }

    // ========================================================================
    // DXF утилиты
    // ========================================================================

    private void writeHeader(PrintWriter out) {
        out.println("0"); out.println("SECTION");
        out.println("2"); out.println("HEADER");
        out.println("0"); out.println("ENDSEC");
        out.println("0"); out.println("SECTION");
        out.println("2"); out.println("ENTITIES");
    }

    private void writeFooter(PrintWriter out) {
        out.println("0"); out.println("ENDSEC");
        out.println("0"); out.println("EOF");
    }

    private void addLine(PrintWriter out, double x1, double y1, double x2, double y2, String layer) {
        out.println("0"); out.println("LINE");
        out.println("8"); out.println(layer);
        out.println("10"); out.println(x1);
        out.println("20"); out.println(y1);
        out.println("11"); out.println(x2);
        out.println("21"); out.println(y2);
    }

    private void addRectangle(PrintWriter out, double x, double y, double w, double h, String layer) {
        addLine(out, x, y, x + w, y, layer);
        addLine(out, x + w, y, x + w, y + h, layer);
        addLine(out, x + w, y + h, x, y + h, layer);
        addLine(out, x, y + h, x, y, layer);
    }

    // CLOSED LWPOLYLINE
    private void addLwPolylineClosed(PrintWriter out, List<Point> pts, String layer) {
        out.println("0");
        out.println("LWPOLYLINE");
        out.println("8");
        out.println(layer);

        out.println("90"); // vertex count
        out.println(pts.size());

        out.println("70"); // flags: 1 = closed
        out.println(1);

        for (Point p : pts) {
            out.println("10"); out.println(p.x);
            out.println("20"); out.println(p.y);
        }
    }

    // ========================================================================
    // Геометрия: hex + клиппинг
    // ========================================================================

    private List<Point> buildHexagon(double cx, double cy, double a) {
        double r = a;
        double h = Math.sqrt(3.0) * a / 2.0;

        double x0 = cx - a / 2.0;
        double x1 = cx + a / 2.0;
        double xLeft = cx - r;
        double xRight = cx + r;
        double yTop = cy - h;
        double yBottom = cy + h;

        List<Point> p = new ArrayList<>(6);
        p.add(new Point(x0, yTop));
        p.add(new Point(x1, yTop));
        p.add(new Point(xRight, cy));
        p.add(new Point(x1, yBottom));
        p.add(new Point(x0, yBottom));
        p.add(new Point(xLeft, cy));
        return p;
    }

    private List<Point> clipPolygonToRect(List<Point> subject, double minX, double maxX, double minY, double maxY) {
        List<Point> out = subject;
        out = clipAgainstEdge(out, p -> p.x >= minX, (a, b) -> intersectX(minX, a, b));
        if (out.isEmpty()) return out;
        out = clipAgainstEdge(out, p -> p.x <= maxX, (a, b) -> intersectX(maxX, a, b));
        if (out.isEmpty()) return out;
        out = clipAgainstEdge(out, p -> p.y >= minY, (a, b) -> intersectY(minY, a, b));
        if (out.isEmpty()) return out;
        out = clipAgainstEdge(out, p -> p.y <= maxY, (a, b) -> intersectY(maxY, a, b));
        return out;
    }

    private interface InsideTest { boolean inside(Point p); }
    private interface Intersector { Point intersect(Point a, Point b); }

    private List<Point> clipAgainstEdge(List<Point> input, InsideTest inside, Intersector intersector) {
        List<Point> output = new ArrayList<>();
        if (input.isEmpty()) return output;

        Point S = input.get(input.size() - 1);
        boolean S_in = inside.inside(S);

        for (Point E : input) {
            boolean E_in = inside.inside(E);

            if (E_in) {
                if (!S_in) output.add(intersector.intersect(S, E));
                output.add(E);
            } else {
                if (S_in) output.add(intersector.intersect(S, E));
            }
            S = E;
            S_in = E_in;
        }
        return output;
    }

    private Point intersectX(double x, Point a, Point b) {
        if (Math.abs(b.x - a.x) < 1e-9) return new Point(x, a.y);
        double t = (x - a.x) / (b.x - a.x);
        double y = a.y + t * (b.y - a.y);
        return new Point(x, y);
    }

    private Point intersectY(double y, Point a, Point b) {
        if (Math.abs(b.y - a.y) < 1e-9) return new Point(a.x, y);
        double t = (y - a.y) / (b.y - a.y);
        double x = a.x + t * (b.x - a.x);
        return new Point(x, y);
    }

    private static final class Point {
        final double x;
        final double y;
        Point(double x, double y) { this.x = x; this.y = y; }
    }
}
