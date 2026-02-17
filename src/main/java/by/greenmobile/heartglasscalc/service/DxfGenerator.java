package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Генерация DXF:
 * - рамка стекла, зона отступа;
 * - шины (горизонтальные или вертикальные) внутри рабочей зоны;
 * - либо зигзаг, либо решётка сот в слое ABLATION.
 *
 * ВАЖНО: соты клиппятся по рабочей области (safe-zone минус шины),
 * чтобы у края получались ЗАКРЫТЫЕ обрезанные контуры.
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
    // СОТЫ (с клиппингом)
    // ========================================================================

    private String generateHoneycombDxf(GlassParameters params) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide();
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // Рабочая область (safe-zone минус шины)
        ClipRect clip = computeWorkingClipRect(width, height, offset, busbarWidth, verticalBusbars);

        // Кол-во берем из params + запас
        double clipW = clip.xMax - clip.xMin;
        double clipH = clip.yMax - clip.yMin;

        int cols = (int) Math.ceil(clipW / stepX) + 6;
        int rows = (int) Math.ceil(clipH / stepY) + 6;

        double safeTop = offset;
        double safeBottom = height - offset;
        double safeLeft = offset;
        double safeRight = width - offset;

        log.debug("DXF соты: width={} height={} offset={} a={} gap={} cols={} rows={} clip={} orientation={}",
                width, height, offset, a, gap, cols, rows, clip,
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

        // Старт с запасом
        double startX = clip.xMin - 3 * stepX;
        double startY = clip.yMin - 3 * stepY + (hexHeight / 2.0);

        int drawn = 0;
        int clippedDrawn = 0;

        for (int col = 0; col < cols; col++) {
            double cxBase = startX + col * stepX;
            double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

            for (int row = 0; row < rows; row++) {
                double cy = startY + row * stepY + colOffsetY;

                // быстрый reject по bbox
                double minX = cxBase - a;
                double maxX = cxBase + a;
                double minY = cy - hexHeight / 2.0;
                double maxY = cy + hexHeight / 2.0;
                if (maxX < clip.xMin || minX > clip.xMax || maxY < clip.yMin || minY > clip.yMax) {
                    continue;
                }

                List<Point> hex = buildFlatTopHex(cxBase, cy, a);
                List<Point> poly = clipPolygonToRect(hex, clip);

                if (poly.size() >= 3) {
                    // Пишем замкнутую полилинию, чтобы контур был закрыт
                    addLwPolylineClosed(out, poly, "ABLATION");
                    drawn++;
                    if (poly.size() != 6) clippedDrawn++;
                }
            }
        }

        writeFooter(out);
        out.flush();

        log.info("DXF соты: отрисовано {} контуров, из них обрезанных (клип) {}", drawn, clippedDrawn);
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

    /**
     * Замкнутая полилиния (LWPOLYLINE) — правильный способ рисовать замкнутые контуры в DXF.
     */
    private void addLwPolylineClosed(PrintWriter out, List<Point> pts, String layer) {
        out.println("0");
        out.println("LWPOLYLINE");
        out.println("8");
        out.println(layer);
        out.println("90"); // vertex count
        out.println(pts.size());
        out.println("70"); // flags: 1 = closed
        out.println("1");

        for (Point p : pts) {
            out.println("10");
            out.println(p.x);
            out.println("20");
            out.println(p.y);
        }
    }

    // ========================================================================
    // КЛИППИНГ ПОЛИГОНОВ ПО ПРЯМОУГОЛЬНИКУ (Sutherland–Hodgman)
    // ========================================================================

    private static final class Point {
        final double x;
        final double y;
        Point(double x, double y) { this.x = x; this.y = y; }
    }

    private static final class ClipRect {
        final double xMin, yMin, xMax, yMax;
        ClipRect(double xMin, double yMin, double xMax, double yMax) {
            this.xMin = xMin; this.yMin = yMin; this.xMax = xMax; this.yMax = yMax;
        }
        boolean isValid() { return xMax > xMin && yMax > yMin; }
        @Override public String toString() {
            return "Rect[xMin=" + xMin + ",yMin=" + yMin + ",xMax=" + xMax + ",yMax=" + yMax + "]";
        }
    }

    private ClipRect computeWorkingClipRect(double width, double height, double offset, double busbarWidth, boolean verticalBusbars) {
        double xMin, xMax, yMin, yMax;
        if (verticalBusbars) {
            xMin = offset;
            xMax = width - offset;
            yMin = offset + busbarWidth;
            yMax = height - offset - busbarWidth;
        } else {
            xMin = offset + busbarWidth;
            xMax = width - offset - busbarWidth;
            yMin = offset;
            yMax = height - offset;
        }
        return new ClipRect(xMin, yMin, xMax, yMax);
    }

    private List<Point> buildFlatTopHex(double cx, double cy, double a) {
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

    private List<Point> clipPolygonToRect(List<Point> subject, ClipRect r) {
        if (subject == null || subject.size() < 3) return Collections.emptyList();
        if (!r.isValid()) return Collections.emptyList();

        List<Point> out = subject;

        out = clipAgainstVertical(out, r.xMin, true);
        if (out.size() < 3) return Collections.emptyList();

        out = clipAgainstVertical(out, r.xMax, false);
        if (out.size() < 3) return Collections.emptyList();

        out = clipAgainstHorizontal(out, r.yMin, true);
        if (out.size() < 3) return Collections.emptyList();

        out = clipAgainstHorizontal(out, r.yMax, false);
        if (out.size() < 3) return Collections.emptyList();

        return out;
    }

    private List<Point> clipAgainstVertical(List<Point> in, double xEdge, boolean keepGreater) {
        if (in.size() < 3) return Collections.emptyList();
        List<Point> out = new ArrayList<>();

        Point prev = in.get(in.size() - 1);
        boolean prevInside = keepGreater ? (prev.x >= xEdge) : (prev.x <= xEdge);

        for (Point curr : in) {
            boolean currInside = keepGreater ? (curr.x >= xEdge) : (curr.x <= xEdge);

            if (currInside) {
                if (!prevInside) out.add(intersectWithVertical(prev, curr, xEdge));
                out.add(curr);
            } else if (prevInside) {
                out.add(intersectWithVertical(prev, curr, xEdge));
            }

            prev = curr;
            prevInside = currInside;
        }
        return out;
    }

    private List<Point> clipAgainstHorizontal(List<Point> in, double yEdge, boolean keepGreater) {
        if (in.size() < 3) return Collections.emptyList();
        List<Point> out = new ArrayList<>();

        Point prev = in.get(in.size() - 1);
        boolean prevInside = keepGreater ? (prev.y >= yEdge) : (prev.y <= yEdge);

        for (Point curr : in) {
            boolean currInside = keepGreater ? (curr.y >= yEdge) : (curr.y <= yEdge);

            if (currInside) {
                if (!prevInside) out.add(intersectWithHorizontal(prev, curr, yEdge));
                out.add(curr);
            } else if (prevInside) {
                out.add(intersectWithHorizontal(prev, curr, yEdge));
            }

            prev = curr;
            prevInside = currInside;
        }
        return out;
    }

    private Point intersectWithVertical(Point a, Point b, double xEdge) {
        double dx = b.x - a.x;
        if (Math.abs(dx) < 1e-12) return new Point(xEdge, a.y);
        double t = (xEdge - a.x) / dx;
        double y = a.y + t * (b.y - a.y);
        return new Point(xEdge, y);
    }

    private Point intersectWithHorizontal(Point a, Point b, double yEdge) {
        double dy = b.y - a.y;
        if (Math.abs(dy) < 1e-12) return new Point(a.x, yEdge);
        double t = (yEdge - a.y) / dy;
        double x = a.x + t * (b.x - a.x);
        return new Point(x, yEdge);
    }
}
