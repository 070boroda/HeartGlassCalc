package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Генерация DXF:
 * - рамка стекла, зона отступа;
 * - шины (горизонтальные или вертикальные) внутри рабочей зоны;
 * - либо зигзаг, либо решётка сот в слое ABLATION.
 *
 * В режиме "соты":
 * - соты НЕ удаляются рядами
 * - контуры, которые заходят в зону "зазор до шины" (busbarClearanceMm),
 *   ПОДРЕЗАЮТСЯ геометрически прямоугольником рабочей области, и остаются замкнутыми.
 */
@Component
@Slf4j
public class DxfGenerator {

    public String generateDxf(GlassParameters params) {
        if (params == null) {
            log.warn("generateDxf(): params = null");
            return "";
        }
        if (params.isHoneycomb()) {
            log.info("DXF: режим сот (honeycomb)");
            return generateHoneycombDxf(params);
        } else {
            log.info("DXF: режим зигзаг");
            return generateZigzagDxf(params);
        }
    }

    // ========================================================================
    // ЗИГЗАГ
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

        log.debug("DXF зигзаг: width={} height={} offset={} lines={} spacing={} orientation={}",
                width, height, offset, lineCount, spacing,
                verticalBusbars ? "верх/низ" : "лево/право");

        writeHeader(out);

        // рамка
        addRectangle(out, 0, 0, width, height, "GLASS");

        // зона отступа
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            addRectangle(out, offset, offset,
                    width - 2 * offset,
                    height - 2 * offset,
                    "SAFE_ZONE");
        }

        // шины
        if (verticalBusbars) {
            addRectangle(out, safeLeft, safeTop,
                    width - 2 * offset, busbarWidth, "BUSBAR");
            addRectangle(out, safeLeft, safeBottom - busbarWidth,
                    width - 2 * offset, busbarWidth, "BUSBAR");
        } else {
            addRectangle(out, safeLeft, safeTop,
                    busbarWidth, safeBottom - safeTop, "BUSBAR");
            addRectangle(out, safeRight - busbarWidth, safeTop,
                    busbarWidth, safeBottom - safeTop, "BUSBAR");
        }

        // линии абляции
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
    // СОТЫ
    // ========================================================================

    private String generateHoneycombDxf(GlassParameters params) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide() != null ? params.getHexSide() : 0.0;
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        // NEW: зазор до шины (если null -> gap)
        double clearance = (params.getBusbarClearanceMm() != null)
                ? Math.max(0.0, params.getBusbarClearanceMm())
                : Math.max(0.0, gap);

        Integer colsObj = params.getHexCols();
        Integer rowsObj = params.getHexRows();
        int cols = colsObj != null ? colsObj : 0;
        int rows = rowsObj != null ? rowsObj : 0;

        double safeTop = offset;
        double safeBottom = height - offset;
        double safeLeft = offset;
        double safeRight = width - offset;

        log.debug("DXF соты: width={} height={} offset={} a={} gap={} clearance={} cols={} rows={} orientation={}",
                width, height, offset, a, gap, clearance, cols, rows,
                verticalBusbars ? "верх/низ" : "лево/право");

        writeHeader(out);

        // рамка
        addRectangle(out, 0, 0, width, height, "GLASS");

        // зона отступа
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            addRectangle(out, offset, offset,
                    width - 2 * offset,
                    height - 2 * offset,
                    "SAFE_ZONE");
        }

        // шины
        if (verticalBusbars) {
            addRectangle(out, safeLeft, safeTop,
                    width - 2 * offset, busbarWidth, "BUSBAR");
            addRectangle(out, safeLeft, safeBottom - busbarWidth,
                    width - 2 * offset, busbarWidth, "BUSBAR");
        } else {
            addRectangle(out, safeLeft, safeTop,
                    busbarWidth, safeBottom - safeTop, "BUSBAR");
            addRectangle(out, safeRight - busbarWidth, safeTop,
                    busbarWidth, safeBottom - safeTop, "BUSBAR");
        }

        if (a <= 0 || cols <= 0 || rows <= 0) {
            log.warn("DXF соты: некорректные параметры (a={}, cols={}, rows={}) — соты не рисую", a, cols, rows);
            writeFooter(out);
            out.flush();
            return sw.toString();
        }

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // Прямоугольник, внутри которого разрешена абляция (с учетом шин+clearance)
        Rect clipRect = computeHoneycombClipRect(width, height, offset, busbarWidth, clearance, verticalBusbars);

        // Старт сетки центров сот — ВАЖНО: от clipRect, чтобы сетка покрывала всю клип-область
        double startX = clipRect.xmin - stepX;
        double startY = clipRect.ymin - stepY;

        int drawn = 0;
        int clipped = 0;

        for (int col = -1; col <= cols + 1; col++) {

            double cx = startX + (col + 1) * stepX;
            double colOffsetY = (Math.floorMod(col, 2) == 0)
                    ? 0.0
                    : (stepY / 2.0);

            for (int row = -1; row <= rows + 1; row++) {

                double cy = startY + (row + 1) * stepY + colOffsetY;

                if (cx + a < clipRect.xmin || cx - a > clipRect.xmax) continue;
                if (cy + hexHeight / 2 < clipRect.ymin || cy - hexHeight / 2 > clipRect.ymax)
                    continue;

                List<Point> hex = buildHexagon(cx, cy, a);
                List<Point> clippedPoly = clipPolygonToRect(hex, clipRect);
                if (clippedPoly.size() < 3) continue;

                addPolygonClosed(out, clippedPoly, "ABLATION");
            }
        }

        writeFooter(out);
        out.flush();

        log.info("DXF соты: контуров={} (подрезанных={}), a={} gap={} clearance={} ориентация={}",
                drawn, clipped, a, gap, clearance, verticalBusbars ? "верх/низ" : "лево/право");

        return sw.toString();
    }

    // ========================================================================
    // DXF утилиты
    // ========================================================================

    private void writeHeader(PrintWriter out) {
        out.println("0");
        out.println("SECTION");
        out.println("2");
        out.println("HEADER");
        out.println("0");
        out.println("ENDSEC");
        out.println("0");
        out.println("SECTION");
        out.println("2");
        out.println("ENTITIES");
    }

    private void writeFooter(PrintWriter out) {
        out.println("0");
        out.println("ENDSEC");
        out.println("0");
        out.println("EOF");
    }

    private void addLine(PrintWriter out,
                         double x1, double y1,
                         double x2, double y2,
                         String layer) {
        out.println("0");
        out.println("LINE");
        out.println("8");
        out.println(layer);
        out.println("10");
        out.println(x1);
        out.println("20");
        out.println(y1);
        out.println("11");
        out.println(x2);
        out.println("21");
        out.println(y2);
    }

    private void addRectangle(PrintWriter out,
                              double x, double y,
                              double w, double h,
                              String layer) {
        addLine(out, x, y, x + w, y, layer);
        addLine(out, x + w, y, x + w, y + h, layer);
        addLine(out, x + w, y + h, x, y + h, layer);
        addLine(out, x, y + h, x, y, layer);
    }

    private void addPolygonClosed(PrintWriter out, List<Point> pts, String layer) {
        for (int i = 0; i < pts.size(); i++) {
            Point a = pts.get(i);
            Point b = pts.get((i + 1) % pts.size());
            addLine(out, a.x, a.y, b.x, b.y, layer);
        }
    }

    // ========================================================================
    // Геометрия и клиппинг (та же логика, что и в SVG)
    // ========================================================================

    private static class Point {
        final double x, y;
        Point(double x, double y) { this.x = x; this.y = y; }
    }

    private static class Rect {
        final double xmin, ymin, xmax, ymax;
        Rect(double xmin, double ymin, double xmax, double ymax) {
            this.xmin = xmin; this.ymin = ymin; this.xmax = xmax; this.ymax = ymax;
        }
    }

    private Rect computeHoneycombClipRect(double width, double height, double offset, double busbarWidth,
                                          double clearance, boolean verticalBusbars) {
        double left = offset;
        double right = width - offset;
        double top = offset;
        double bottom = height - offset;

        if (verticalBusbars) {
            top = offset + busbarWidth + clearance;
            bottom = height - offset - busbarWidth - clearance;
        } else {
            left = offset + busbarWidth + clearance;
            right = width - offset - busbarWidth - clearance;
        }

        if (right < left) right = left;
        if (bottom < top) bottom = top;

        return new Rect(left, top, right, bottom);
    }

    private List<Point> buildHexagon(double cx, double cy, double a) {
        double r = a;
        double h = Math.sqrt(3.0) * a / 2.0;

        double x0 = cx - a / 2.0;
        double x1 = cx + a / 2.0;
        double xLeft = cx - r;
        double xRight = cx + r;
        double yTop = cy - h;
        double yBottom = cy + h;

        List<Point> pts = new ArrayList<>(6);
        pts.add(new Point(x0, yTop));
        pts.add(new Point(x1, yTop));
        pts.add(new Point(xRight, cy));
        pts.add(new Point(x1, yBottom));
        pts.add(new Point(x0, yBottom));
        pts.add(new Point(xLeft, cy));
        return pts;
    }

    private List<Point> clipPolygonToRect(List<Point> poly, Rect r) {
        List<Point> out = poly;
        out = clipByEdge(out, p -> p.x >= r.xmin, (a,b) -> intersectVertical(a,b,r.xmin));
        out = clipByEdge(out, p -> p.x <= r.xmax, (a,b) -> intersectVertical(a,b,r.xmax));
        out = clipByEdge(out, p -> p.y >= r.ymin, (a,b) -> intersectHorizontal(a,b,r.ymin));
        out = clipByEdge(out, p -> p.y <= r.ymax, (a,b) -> intersectHorizontal(a,b,r.ymax));
        return out;
    }

    private interface Inside {
        boolean test(Point p);
    }
    private interface Intersect {
        Point at(Point a, Point b);
    }

    private List<Point> clipByEdge(List<Point> input, Inside inside, Intersect intersect) {
        List<Point> output = new ArrayList<>();
        if (input.isEmpty()) return output;

        Point prev = input.get(input.size() - 1);
        boolean prevIn = inside.test(prev);

        for (Point cur : input) {
            boolean curIn = inside.test(cur);

            if (curIn) {
                if (!prevIn) {
                    output.add(intersect.at(prev, cur));
                }
                output.add(cur);
            } else {
                if (prevIn) {
                    output.add(intersect.at(prev, cur));
                }
            }

            prev = cur;
            prevIn = curIn;
        }
        return output;
    }

    private Point intersectVertical(Point a, Point b, double x) {
        double dx = b.x - a.x;
        if (Math.abs(dx) < 1e-12) return new Point(x, a.y);
        double t = (x - a.x) / dx;
        double y = a.y + t * (b.y - a.y);
        return new Point(x, y);
    }

    private Point intersectHorizontal(Point a, Point b, double y) {
        double dy = b.y - a.y;
        if (Math.abs(dy) < 1e-12) return new Point(a.x, y);
        double t = (y - a.y) / dy;
        double x = a.x + t * (b.x - a.x);
        return new Point(x, y);
    }
}
