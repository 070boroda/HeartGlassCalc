package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SVG-чертёж:
 * - рамка стекла, зона отступа, шины;
 * - либо зигзаг, либо решётка сот.
 * <p>
 * ВАЖНО (для сот):
 * - сетка строится "с запасом" и покрывает всю зону
 * - соты у границ/шин НЕ отбрасываются, а ПОДРЕЗАЮТСЯ клиппингом по прямоугольнику
 * - результат всегда "закрытый контур" (полигон), как на твоём рисунке
 */
@Service
@Slf4j
public class SvgGeneratorService {

    public String generateSvg(GlassParameters params) {
        if (params == null) {
            log.warn("generateSvg(): params = null");
            return "";
        }
        return generateHoneycombSvg(params);
    }

    // ========================================================================
    // ЗИГЗАГ (оставил как было)
    // ========================================================================

    // ========================================================================
    // СОТЫ (подрезка по зоне между шинами + закрытые контуры)
    // ========================================================================

    private String generateHoneycombSvg(GlassParameters params) {
        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide() != null ? params.getHexSide() : 30.0;
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        // Зазор от шин: если не задан — используем gap (обычно логично)
        double clearance = (params.getBusbarClearanceMm() != null && params.getBusbarClearanceMm() >= 0)
                ? params.getBusbarClearanceMm()
                : gap;

        double padding = 50.0;

        // Геометрия сот
        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // Безопасные границы по стеклу (красная зона отступа)
        double safeLeft = offset;
        double safeRight = width - offset;
        double safeTop = offset;
        double safeBottom = height - offset;

        // Допустимая зона для сот: "между шинами", но НЕ вплотную — с clearance
        double clipMinX, clipMaxX, clipMinY, clipMaxY;
        if (verticalBusbars) {
            // шины сверху/снизу, соты между ними
            clipMinX = safeLeft;
            clipMaxX = safeRight;
            clipMinY = safeTop + busbarWidth + clearance;
            clipMaxY = safeBottom - busbarWidth - clearance;
        } else {
            // шины слева/справа, соты между ними
            clipMinX = safeLeft + busbarWidth + clearance;
            clipMaxX = safeRight - busbarWidth - clearance;
            clipMinY = safeTop;
            clipMaxY = safeBottom;
        }

        // если зона выродилась — рисуем только рамку/шины
        if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) {
            log.warn("SVG соты: рабочая зона выродилась (clearance={} мм).", clearance);
            return baseSvgOnly(width, height, offset, busbarWidth, verticalBusbars, padding);
        }

        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("width=\"").append(width + 2 * padding).append("mm\" ")
                .append("height=\"").append(height + 2 * padding).append("mm\" ")
                .append("viewBox=\"")
                .append(-padding).append(" ")
                .append(-padding).append(" ")
                .append(width + 2 * padding).append(" ")
                .append(height + 2 * padding).append("\">\n");

        // фон
        svg.append("  <rect x=\"").append(-padding).append("\" y=\"").append(-padding)
                .append("\" width=\"").append(width + 2 * padding)
                .append("\" height=\"").append(height + 2 * padding)
                .append("\" fill=\"#e9f3ff\" />\n");

        // стекло
        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\" stroke=\"none\" />\n");

        // тень+рамка
        svg.append("  <defs>\n");
        svg.append("    <filter id=\"glassShadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n");
        svg.append("      <feDropShadow dx=\"4\" dy=\"4\" stdDeviation=\"4\" flood-color=\"#999\" flood-opacity=\"0.5\" />\n");
        svg.append("    </filter>\n");
        svg.append("  </defs>\n");

        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"none\" stroke=\"#000000\" stroke-width=\"3\" filter=\"url(#glassShadow)\" />\n");

        // зона отступа (красный пунктир)
        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            svg.append("  <rect x=\"").append(offset)
                    .append("\" y=\"").append(offset)
                    .append("\" width=\"").append(width - 2 * offset)
                    .append("\" height=\"").append(height - 2 * offset)
                    .append("\" fill=\"none\" stroke=\"#ff0000\" stroke-width=\"2\" stroke-dasharray=\"10,6\" />\n");
        }

        // шины
        if (verticalBusbars) {
            drawHorizontalBusbars(svg, width, height, offset, busbarWidth);
        } else {
            drawVerticalBusbars(svg, width, height, offset, busbarWidth);
        }

        // Рисуем соты как закрытые полигоны после клиппинга
        svg.append("  <g stroke=\"#c0d2e8\" stroke-width=\"1.2\" fill=\"none\" opacity=\"0.85\">\n");

        // ---- Сетка "с запасом" ----
        // Берём диапазоны col/row так, чтобы покрыть clip-область + небольшой запас
        // Стартуем примерно слева "до зоны", чтобы не было пустой полосы.
        int colMin = (int) Math.floor((clipMinX - 2 * a) / stepX) - 2;
        int colMax = (int) Math.ceil((clipMaxX + 2 * a) / stepX) + 2;

        // По Y сложнее из-за шахматного смещения, но берём широкий диапазон
        int rowMin = (int) Math.floor((clipMinY - 2 * hexHeight) / stepY) - 3;
        int rowMax = (int) Math.ceil((clipMaxY + 2 * hexHeight) / stepY) + 3;

        int drawn = 0;
        int clippedCount = 0;

        for (int col = colMin; col <= colMax; col++) {
            double cxBase = (col * stepX);

            // чтобы сетка сидела "красиво" внутри стекла, добавим привязку к safeLeft
            cxBase += safeLeft + a;

            double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

            for (int row = rowMin; row <= rowMax; row++) {
                double cy = safeTop + (hexHeight / 2.0) + (row * stepY) + colOffsetY;

                // Быстрый отбор по bbox до клиппинга (чтобы не клиппить лишнее)
                if (cxBase + a < clipMinX - 1) continue;
                if (cxBase - a > clipMaxX + 1) continue;
                if (cy + hexHeight / 2.0 < clipMinY - 1) continue;
                if (cy - hexHeight / 2.0 > clipMaxY + 1) continue;

                List<Point> hex = buildHexagon(cxBase, cy, a);
                List<Point> clipped = clipPolygonToRect(hex, clipMinX, clipMaxX, clipMinY, clipMaxY);

                if (clipped.size() < 3) {
                    continue;
                }

                if (clipped.size() != hex.size()) clippedCount++;

                appendPolygon(svg, clipped);
                drawn++;
            }
        }

        svg.append("  </g>\n");
        svg.append("</svg>");

        log.info("SVG соты: контуров={} (подрезанных={}), a={} gap={} clearance={} ориентация={}",
                drawn, clippedCount, a, gap, clearance, verticalBusbars ? "верх/низ" : "лево/право");

        return svg.toString();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String baseSvgOnly(double width, double height, double offset,
                               double busbarWidth, boolean verticalBusbars, double padding) {
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("width=\"").append(width + 2 * padding).append("mm\" ")
                .append("height=\"").append(height + 2 * padding).append("mm\" ")
                .append("viewBox=\"")
                .append(-padding).append(" ")
                .append(-padding).append(" ")
                .append(width + 2 * padding).append(" ")
                .append(height + 2 * padding).append("\">\n");

        svg.append("  <rect x=\"").append(-padding).append("\" y=\"").append(-padding)
                .append("\" width=\"").append(width + 2 * padding)
                .append("\" height=\"").append(height + 2 * padding)
                .append("\" fill=\"#e9f3ff\" />\n");

        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\" stroke=\"none\" />\n");

        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"none\" stroke=\"#000\" stroke-width=\"3\" />\n");

        if (offset > 0 && width > 2 * offset && height > 2 * offset) {
            svg.append("  <rect x=\"").append(offset)
                    .append("\" y=\"").append(offset)
                    .append("\" width=\"").append(width - 2 * offset)
                    .append("\" height=\"").append(height - 2 * offset)
                    .append("\" fill=\"none\" stroke=\"#ff0000\" stroke-width=\"2\" stroke-dasharray=\"10,6\" />\n");
        }

        if (verticalBusbars) drawHorizontalBusbars(svg, width, height, offset, busbarWidth);
        else drawVerticalBusbars(svg, width, height, offset, busbarWidth);

        svg.append("</svg>");
        return svg.toString();
    }

    private void drawHorizontalBusbars(StringBuilder svg,
                                       double width,
                                       double height,
                                       double offset,
                                       double busbarWidth) {
        double safeTop = offset;
        double safeBottom = height - offset;

        svg.append("  <rect x=\"").append(offset)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(width - 2 * offset)
                .append("\" height=\"").append(busbarWidth)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        svg.append("  <rect x=\"").append(offset)
                .append("\" y=\"").append(safeBottom - busbarWidth)
                .append("\" width=\"").append(width - 2 * offset)
                .append("\" height=\"").append(busbarWidth)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");
    }

    private void drawVerticalBusbars(StringBuilder svg,
                                     double width,
                                     double height,
                                     double offset,
                                     double busbarWidth) {
        double safeTop = offset;
        double safeBottom = height - offset;

        svg.append("  <rect x=\"").append(offset)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(busbarWidth)
                .append("\" height=\"").append(safeBottom - safeTop)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        svg.append("  <rect x=\"").append(width - offset - busbarWidth)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(busbarWidth)
                .append("\" height=\"").append(safeBottom - safeTop)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");
    }

    // ---------- Polygon drawing ----------
    private void appendPolygon(StringBuilder svg, List<Point> poly) {
        svg.append("    <path d=\"");
        svg.append("M ").append(fmt(poly.get(0).x)).append(" ").append(fmt(poly.get(0).y)).append(" ");
        for (int i = 1; i < poly.size(); i++) {
            svg.append("L ").append(fmt(poly.get(i).x)).append(" ").append(fmt(poly.get(i).y)).append(" ");
        }
        svg.append("Z\" />\n");
    }

    private String fmt(double v) {
        // компактный вывод
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    // ---------- Hexagon vertices ----------
    private List<Point> buildHexagon(double cx, double cy, double a) {
        // "плоская вершина" (flat-top) как в твоём текущем коде
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

    // ---------- Sutherland–Hodgman clip to rectangle ----------
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

    private interface InsideTest {
        boolean inside(Point p);
    }

    private interface Intersector {
        Point intersect(Point a, Point b);
    }

    private List<Point> clipAgainstEdge(List<Point> input, InsideTest inside, Intersector intersector) {
        List<Point> output = new ArrayList<>();
        if (input.isEmpty()) return output;

        Point S = input.get(input.size() - 1);
        boolean S_in = inside.inside(S);

        for (Point E : input) {
            boolean E_in = inside.inside(E);

            if (E_in) {
                if (!S_in) {
                    output.add(intersector.intersect(S, E));
                }
                output.add(E);
            } else {
                if (S_in) {
                    output.add(intersector.intersect(S, E));
                }
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

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
