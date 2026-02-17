package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Генерация SVG-чертежа:
 * - рамка стекла, зона отступа, шины;
 * - либо зигзаг, либо решётка сот.
 *
 * ВАЖНО: соты клиппятся по рабочей области (safe-zone минус шины),
 * чтобы у края получались ЗАКРЫТЫЕ обрезанные контуры.
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
                .append("\" fill=\"#f0f4ff\" />\n");

        // стекло
        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"#ffffff\" stroke=\"none\" />\n");

        // тень
        svg.append("  <defs>\n");
        svg.append("    <filter id=\"glassShadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n");
        svg.append("      <feDropShadow dx=\"4\" dy=\"4\" stdDeviation=\"4\" flood-color=\"#999\" flood-opacity=\"0.5\" />\n");
        svg.append("    </filter>\n");
        svg.append("  </defs>\n");

        // рамка
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

        // линии абляции
        if (lineCount > 0 && spacing > 0) {
            if (verticalBusbars) {
                double safeTop = offset + busbarWidth;
                double safeBottom = height - offset - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double x = offset + i * spacing;
                    if (x > width - offset) continue;
                    svg.append("  <line x1=\"").append(x).append("\" y1=\"").append(safeTop)
                            .append("\" x2=\"").append(x).append("\" y2=\"").append(safeBottom)
                            .append("\" stroke=\"#0055ff\" stroke-width=\"2\" />\n");
                }
            } else {
                double safeLeft = offset + busbarWidth;
                double safeRight = width - offset - busbarWidth;
                for (int i = 1; i <= lineCount; i++) {
                    double y = offset + i * spacing;
                    if (y > height - offset) continue;
                    svg.append("  <line x1=\"").append(safeLeft).append("\" y1=\"").append(y)
                            .append("\" x2=\"").append(safeRight).append("\" y2=\"").append(y)
                            .append("\" stroke=\"#0055ff\" stroke-width=\"2\" />\n");
                }
            }
        }

        svg.append("</svg>");
        return svg.toString();
    }

    // ========================================================================
    // СОТЫ (с клиппингом)
    // ========================================================================

    private String generateHoneycombSvg(GlassParameters params) {
        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide();
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        double padding = 50.0;

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // Рабочая область (safe-zone минус шины)
        ClipRect clip = computeWorkingClipRect(width, height, offset, busbarWidth, verticalBusbars);
        if (!clip.isValid()) {
            log.warn("SVG соты: рабочая область некорректна, вернём пустую сетку");
        }

// Считаем, сколько колонок/рядов нужно, чтобы ПОЛНОСТЬЮ перекрыть clip-область,
// плюс запас, чтобы клипнутые соты у границ гарантированно появились.
        double clipW = clip.xMax - clip.xMin;
        double clipH = clip.yMax - clip.yMin;

// +6 — это запас в шагах (можно 4..8, 6 даёт стабильное перекрытие)
        int cols = (int) Math.ceil(clipW / stepX) + 6;
        int rows = (int) Math.ceil(clipH / stepY) + 6;




        log.debug("SVG соты: width={} height={} offset={} a={} gap={} cols={} rows={} clip={} orientation={}",
                width, height, offset, a, gap, cols, rows, clip,
                verticalBusbars ? "верх/низ" : "лево/право");

        StringBuilder svg = new StringBuilder();

        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
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

        // тень и рамка
        svg.append("  <defs>\n");
        svg.append("    <filter id=\"glassShadow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n");
        svg.append("      <feDropShadow dx=\"4\" dy=\"4\" stdDeviation=\"4\" flood-color=\"#999\" flood-opacity=\"0.5\" />\n");
        svg.append("    </filter>\n");
        svg.append("  </defs>\n");

        svg.append("  <rect x=\"0\" y=\"0\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" fill=\"none\" stroke=\"#000000\" stroke-width=\"3\" filter=\"url(#glassShadow)\" />\n");

        // зона отступа
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

// Стартуем левее/выше clip-области на несколько шагов,
// чтобы первые соты тоже клипнулись и дали закрытые контуры у краёв.
        double startX = clip.xMin - 3 * stepX;
        double startY = clip.yMin - 3 * stepY + (hexHeight / 2.0);

        svg.append("  <g stroke=\"#c0d2e8\" stroke-width=\"1.2\" fill=\"none\" opacity=\"0.75\">\n");

        int drawn = 0;
        int clippedDrawn = 0;

        for (int col = 0; col < cols; col++) {
            double cxBase = startX + col * stepX;
            double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

            for (int row = 0; row < rows; row++) {
                double cy = startY + row * stepY + colOffsetY;

                // быстрый reject по bbox шестиугольника (чтобы не клиппить лишнее)
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
                    svg.append("    <path d=\"").append(toSvgPathClosed(poly)).append("\" />\n");
                    drawn++;
                    if (poly.size() != 6) clippedDrawn++;
                }
            }
        }

        svg.append("  </g>\n");
        svg.append("</svg>");

        log.info("SVG соты: отрисовано {} контуров, из них обрезанных (клип) {}", drawn, clippedDrawn);
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
        svg.append("  <rect x=\"").append(offset)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(width - 2 * offset)
                .append("\" height=\"").append(busbarWidth)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        // нижняя шина
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

        // левая шина
        svg.append("  <rect x=\"").append(offset)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(busbarWidth)
                .append("\" height=\"").append(safeBottom - safeTop)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");

        // правая шина
        svg.append("  <rect x=\"").append(width - offset - busbarWidth)
                .append("\" y=\"").append(safeTop)
                .append("\" width=\"").append(busbarWidth)
                .append("\" height=\"").append(safeBottom - safeTop)
                .append("\" fill=\"#c0c0c0\" stroke=\"#808080\" stroke-width=\"1\" />\n");
    }

    // ========================================================================
    // КЛИППИНГ ПОЛИГОНОВ ПО ПРЯМОУГОЛЬНИКУ (Sutherland–Hodgman)
    // ========================================================================

    private static final class Point {
        final double x;
        final double y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class ClipRect {
        final double xMin, yMin, xMax, yMax;

        ClipRect(double xMin, double yMin, double xMax, double yMax) {
            this.xMin = xMin;
            this.yMin = yMin;
            this.xMax = xMax;
            this.yMax = yMax;
        }

        boolean isValid() {
            return xMax > xMin && yMax > yMin;
        }

        @Override
        public String toString() {
            return "Rect[xMin=" + xMin + ",yMin=" + yMin + ",xMax=" + xMax + ",yMax=" + yMax + "]";
        }
    }

    private ClipRect computeWorkingClipRect(double width, double height, double offset, double busbarWidth, boolean verticalBusbars) {
        double xMin, xMax, yMin, yMax;
        if (verticalBusbars) {
            // шины сверху/снизу -> ограничиваем по Y
            xMin = offset;
            xMax = width - offset;
            yMin = offset + busbarWidth;
            yMax = height - offset - busbarWidth;
        } else {
            // шины слева/справа -> ограничиваем по X
            xMin = offset + busbarWidth;
            xMax = width - offset - busbarWidth;
            yMin = offset;
            yMax = height - offset;
        }
        return new ClipRect(xMin, yMin, xMax, yMax);
    }

    // Плоско-верхний шестиугольник (flat-top), как в твоём исходном коде
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

        // left: x >= xMin
        out = clipAgainstVertical(out, r.xMin, true);
        if (out.size() < 3) return Collections.emptyList();

        // right: x <= xMax
        out = clipAgainstVertical(out, r.xMax, false);
        if (out.size() < 3) return Collections.emptyList();

        // top: y >= yMin
        out = clipAgainstHorizontal(out, r.yMin, true);
        if (out.size() < 3) return Collections.emptyList();

        // bottom: y <= yMax
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
                if (!prevInside) {
                    out.add(intersectWithVertical(prev, curr, xEdge));
                }
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
                if (!prevInside) {
                    out.add(intersectWithHorizontal(prev, curr, yEdge));
                }
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
        if (Math.abs(dx) < 1e-12) {
            return new Point(xEdge, a.y);
        }
        double t = (xEdge - a.x) / dx;
        double y = a.y + t * (b.y - a.y);
        return new Point(xEdge, y);
    }

    private Point intersectWithHorizontal(Point a, Point b, double yEdge) {
        double dy = b.y - a.y;
        if (Math.abs(dy) < 1e-12) {
            return new Point(a.x, yEdge);
        }
        double t = (yEdge - a.y) / dy;
        double x = a.x + t * (b.x - a.x);
        return new Point(x, yEdge);
    }

    private String toSvgPathClosed(List<Point> poly) {
        StringBuilder sb = new StringBuilder();
        Point p0 = poly.get(0);
        sb.append("M ").append(p0.x).append(" ").append(p0.y).append(" ");
        for (int i = 1; i < poly.size(); i++) {
            Point p = poly.get(i);
            sb.append("L ").append(p.x).append(" ").append(p.y).append(" ");
        }
        sb.append("Z");
        return sb.toString();
    }
}
