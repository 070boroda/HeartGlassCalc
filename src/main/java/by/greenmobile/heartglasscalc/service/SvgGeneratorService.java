package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Генерация SVG-чертежа:
 * - рамка стекла, зона отступа, шины;
 * - либо зигзаг, либо решётка сот.
 *
 * В режиме "соты":
 * - соты заполняют рабочую область
 * - возле шин добавляется "зазор до шины" (busbarClearanceMm): контуры сот,
 *   которые заходят в эту зону, ПОДРЕЗАЮТСЯ (геометрически), но остаются ЗАМКНУТЫМИ.
 */
@Service
@Slf4j
public class SvgGeneratorService {

    public String generateSvg(GlassParameters params) {
        if (params == null) {
            log.warn("generateSvg(): params = null");
            return "";
        }
        if (params.isHoneycomb()) {
            log.info("SVG: режим сот (honeycomb)");
            return generateHoneycombSvg(params);
        } else {
            log.info("SVG: режим зигзаг");
            return generateZigzagSvg(params);
        }
    }

    // ========================================================================
    // ЗИГЗАГ
    // ========================================================================

    private String generateZigzagSvg(GlassParameters params) {
        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double spacing = params.getLineSpacing();
        int lineCount = params.getLineCount() != null ? params.getLineCount() : 0;
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double padding = 50.0;

        log.debug("SVG зигзаг: width={} height={} offset={} lines={} spacing={} orientation={}",
                width, height, offset, lineCount, spacing,
                verticalBusbars ? "верх/низ" : "лево/право");

        StringBuilder svg = new StringBuilder();

        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
                .append("width=\"").append(fmt(width + 2 * padding)).append("mm\" ")
                .append("height=\"").append(fmt(height + 2 * padding)).append("mm\" ")
                .append("viewBox=\"")
                .append(fmt(-padding)).append(" ")
                .append(fmt(-padding)).append(" ")
                .append(fmt(width + 2 * padding)).append(" ")
                .append(fmt(height + 2 * padding)).append("\">\n");

        // фон
        svg.append("  <rect x=\"").append(fmt(-padding)).append("\" y=\"").append(fmt(-padding))
                .append("\" width=\"").append(fmt(width + 2 * padding))
                .append("\" height=\"").append(fmt(height + 2 * padding))
                .append("\" fill=\"#f0f4ff\" />\n");

        // стекло
        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(fmt(width))
                .append("\" height=\"").append(fmt(height))
                .append("\" fill=\"#ffffff\" stroke=\"none\" />\n");

        // тень
        svg.append("  <defs>\n");
        svg.append("    <filter id=\"glassShadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n");
        svg.append("      <feDropShadow dx=\"4\" dy=\"4\" stdDeviation=\"4\" flood-color=\"#999\" flood-opacity=\"0.5\" />\n");
        svg.append("    </filter>\n");
        svg.append("  </defs>\n");

        // рамка
        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(fmt(width))
                .append("\" height=\"").append(fmt(height))
                .append("\" fill=\"none\" stroke=\"#000000\" stroke-width=\"3\" filter=\"url(#glassShadow)\" />\n");

        // зона отступа (красный пунктир)
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            svg.append("  <rect x=\"").append(fmt(offset))
                    .append("\" y=\"").append(fmt(offset))
                    .append("\" width=\"").append(fmt(width - 2 * offset))
                    .append("\" height=\"").append(fmt(height - 2 * offset))
                    .append("\" fill=\"none\" stroke=\"#ff0000\" stroke-width=\"2\" stroke-dasharray=\"10,6\" />\n");
        }

        // шины
        if (verticalBusbars) {
            drawHorizontalBusbars(svg, width, height, offset, busbarWidth);
        } else {
            drawVerticalBusbars(svg, width, height, offset, busbarWidth);
        }

        // линии абляции
        if (lineCount > 0 && spacing > 0) {
            if (verticalBusbars) {
                double safeTop = offset + busbarWidth;
                double safeBottom = height - offset - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double x = offset + i * spacing;
                    if (x > width - offset) continue;
                    svg.append("  <line x1=\"").append(fmt(x)).append("\" y1=\"").append(fmt(safeTop))
                            .append("\" x2=\"").append(fmt(x)).append("\" y2=\"").append(fmt(safeBottom))
                            .append("\" stroke=\"#0055ff\" stroke-width=\"2\" />\n");
                }
            } else {
                double safeLeft = offset + busbarWidth;
                double safeRight = width - offset - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double y = offset + i * spacing;
                    if (y > height - offset) continue;
                    svg.append("  <line x1=\"").append(fmt(safeLeft)).append("\" y1=\"").append(fmt(y))
                            .append("\" x2=\"").append(fmt(safeRight)).append("\" y2=\"").append(fmt(y))
                            .append("\" stroke=\"#0055ff\" stroke-width=\"2\" />\n");
                }
            }
        }

        svg.append("</svg>");
        return svg.toString();
    }

    // ========================================================================
    // СОТЫ
    // ========================================================================

    private String generateHoneycombSvg(GlassParameters params) {
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

        double padding = 50.0;

        if (a <= 0 || cols <= 0 || rows <= 0) {
            log.warn("SVG соты: некорректные параметры (a={}, cols={}, rows={}), верну только стекло+шины", a, cols, rows);
        }

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        log.debug("SVG соты: width={} height={} offset={} a={} gap={} clearance={} cols={} rows={} orientation={}",
                width, height, offset, a, gap, clearance, cols, rows,
                verticalBusbars ? "верх/низ" : "лево/право");

        StringBuilder svg = new StringBuilder();

        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
                .append("width=\"").append(fmt(width + 2 * padding)).append("mm\" ")
                .append("height=\"").append(fmt(height + 2 * padding)).append("mm\" ")
                .append("viewBox=\"")
                .append(fmt(-padding)).append(" ")
                .append(fmt(-padding)).append(" ")
                .append(fmt(width + 2 * padding)).append(" ")
                .append(fmt(height + 2 * padding)).append("\">\n");

        // фон
        svg.append("  <rect x=\"").append(fmt(-padding)).append("\" y=\"").append(fmt(-padding))
                .append("\" width=\"").append(fmt(width + 2 * padding))
                .append("\" height=\"").append(fmt(height + 2 * padding))
                .append("\" fill=\"#e9f3ff\" />\n");

        // стекло
        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(fmt(width))
                .append("\" height=\"").append(fmt(height))
                .append("\" fill=\"#ffffff\" stroke=\"none\" />\n");

        // тень и рамка
        svg.append("  <defs>\n");
        svg.append("    <filter id=\"glassShadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n");
        svg.append("      <feDropShadow dx=\"4\" dy=\"4\" stdDeviation=\"4\" flood-color=\"#999\" flood-opacity=\"0.5\" />\n");
        svg.append("    </filter>\n");
        svg.append("  </defs>\n");

        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(fmt(width))
                .append("\" height=\"").append(fmt(height))
                .append("\" fill=\"none\" stroke=\"#000000\" stroke-width=\"3\" filter=\"url(#glassShadow)\" />\n");

        // зона отступа
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            svg.append("  <rect x=\"").append(fmt(offset))
                    .append("\" y=\"").append(fmt(offset))
                    .append("\" width=\"").append(fmt(width - 2 * offset))
                    .append("\" height=\"").append(fmt(height - 2 * offset))
                    .append("\" fill=\"none\" stroke=\"#ff0000\" stroke-width=\"2\" stroke-dasharray=\"10,6\" />\n");
        }

        // шины
        if (verticalBusbars) {
            drawHorizontalBusbars(svg, width, height, offset, busbarWidth);
        } else {
            drawVerticalBusbars(svg, width, height, offset, busbarWidth);
        }

        // ===== рабочая зона для сот с учетом clearance =====
        Rect clipRect = computeHoneycombClipRect(
                width, height, offset, busbarWidth, clearance, verticalBusbars
        );

        // Старт сетки центров сот — ВАЖНО: от clipRect, чтобы сетка покрывала всю клип-область
        double startX = clipRect.xmin + a;
        double startY = clipRect.ymin + hexHeight / 2.0;

        // Рисуем
        svg.append("  <g stroke=\"#c0d2e8\" stroke-width=\"1.2\" fill=\"none\" opacity=\"0.85\">\n");

        int drawn = 0;
        int clipped = 0;

        if (a > 0 && cols > 0 && rows > 0) {
            for (int col = 0; col < cols; col++) {
                double cxBase = startX + col * stepX;
                double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

                for (int row = 0; row < rows; row++) {
                    double cy = startY + row * stepY + colOffsetY;

                    // исходный шестиугольник (6 точек)
                    List<Point> hex = buildHexagon(cxBase, cy, a);

                    // геометрически подрезаем многоугольник прямоугольником clipRect
                    List<Point> clippedPoly = clipPolygonToRect(hex, clipRect);

                    if (clippedPoly.size() < 3) {
                        continue; // полностью ушёл за пределы
                    }

                    if (clippedPoly.size() != hex.size()) {
                        clipped++;
                    }

                    svg.append("    <path d=\"").append(svgPath(clippedPoly)).append("\" />\n");
                    drawn++;
                }
            }
        }

        svg.append("  </g>\n");
        svg.append("</svg>");

        log.info("SVG соты: контуров={} (подрезанных={}), a={} gap={} clearance={} ориентация={}",
                drawn, clipped, a, gap, clearance, verticalBusbars ? "верх/низ" : "лево/право");

        return svg.toString();
    }

    // ========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ SVG
    // ========================================================================

    private void drawHorizontalBusbars(StringBuilder svg,
                                       double width,
                                       double height,
                                       double offset,
                                       double busbarWidth) {
        double safeTop = offset;
        double safeBottom = height - offset;

        // верхняя шина
        svg.append("  <rect x=\"").append(fmt(offset))
                .append("\" y=\"").append(fmt(safeTop))
                .append("\" width=\"").append(fmt(width - 2 * offset))
                .append("\" height=\"").append(fmt(busbarWidth))
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        // нижняя шина
        svg.append("  <rect x=\"").append(fmt(offset))
                .append("\" y=\"").append(fmt(safeBottom - busbarWidth))
                .append("\" width=\"").append(fmt(width - 2 * offset))
                .append("\" height=\"").append(fmt(busbarWidth))
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");
    }

    private void drawVerticalBusbars(StringBuilder svg,
                                     double width,
                                     double height,
                                     double offset,
                                     double busbarWidth) {
        double safeTop = offset;
        double safeBottom = height - offset;

        // левая шина
        svg.append("  <rect x=\"").append(fmt(offset))
                .append("\" y=\"").append(fmt(safeTop))
                .append("\" width=\"").append(fmt(busbarWidth))
                .append("\" height=\"").append(fmt(safeBottom - safeTop))
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        // правая шина
        svg.append("  <rect x=\"").append(fmt(width - offset - busbarWidth))
                .append("\" y=\"").append(fmt(safeTop))
                .append("\" width=\"").append(fmt(busbarWidth))
                .append("\" height=\"").append(fmt(safeBottom - safeTop))
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");
    }

    // ========================================================================
    // Геометрия и клиппинг
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
            // шины сверху/снизу: ограничение по Y
            top = offset + busbarWidth + clearance;
            bottom = height - offset - busbarWidth - clearance;
        } else {
            // шины слева/справа: ограничение по X
            left = offset + busbarWidth + clearance;
            right = width - offset - busbarWidth - clearance;
        }

        // страховка
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

    /**
     * Клиппинг произвольного многоугольника прямоугольником.
     * Сохраняет замкнутость (на выходе получаем замкнутый контур при рисовании).
     */
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
        if (Math.abs(dx) < 1e-12) {
            return new Point(x, a.y);
        }
        double t = (x - a.x) / dx;
        double y = a.y + t * (b.y - a.y);
        return new Point(x, y);
    }

    private Point intersectHorizontal(Point a, Point b, double y) {
        double dy = b.y - a.y;
        if (Math.abs(dy) < 1e-12) {
            return new Point(a.x, y);
        }
        double t = (y - a.y) / dy;
        double x = a.x + t * (b.x - a.x);
        return new Point(x, y);
    }

    private String svgPath(List<Point> pts) {
        if (pts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Point p0 = pts.get(0);
        sb.append("M ").append(fmt(p0.x)).append(" ").append(fmt(p0.y)).append(" ");
        for (int i = 1; i < pts.size(); i++) {
            Point p = pts.get(i);
            sb.append("L ").append(fmt(p.x)).append(" ").append(fmt(p.y)).append(" ");
        }
        sb.append("Z");
        return sb.toString();
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }
}
