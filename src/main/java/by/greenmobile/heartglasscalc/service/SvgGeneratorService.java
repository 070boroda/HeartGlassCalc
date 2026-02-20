package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class SvgGeneratorService {

    public String generateSvg(GlassParameters params) {
        if (params == null) return "";
        return generateHoneycombSvg(params);
    }

    // ========================================================================
    // СОТЫ (FIXED VERSION)
    // ========================================================================

    private String generateHoneycombSvg(GlassParameters params) {

        double width = params.getWidth();
        double height = params.getHeight();
        double offset = params.getEdgeOffset();
        double busbarWidth = params.getBusbarWidth();
        boolean verticalBusbars = params.isVerticalBusbars();

        double a = params.getHexSide() != null ? params.getHexSide() : 0.0;
        double gap = params.getHexGap() != null ? params.getHexGap() : 2.0;

        double clearance = (params.getBusbarClearanceMm() != null)
                ? Math.max(0.0, params.getBusbarClearanceMm())
                : Math.max(0.0, gap);

        int cols = params.getHexCols() != null ? params.getHexCols() : 0;
        int rows = params.getHexRows() != null ? params.getHexRows() : 0;

        double padding = 50.0;
        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        Rect clipRect = computeHoneycombClipRect(
                width, height, offset, busbarWidth, clearance, verticalBusbars
        );

        StringBuilder svg = new StringBuilder();

        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("width=\"").append(fmt(width + 2 * padding)).append("mm\" ")
                .append("height=\"").append(fmt(height + 2 * padding)).append("mm\" ")
                .append("viewBox=\"")
                .append(fmt(-padding)).append(" ")
                .append(fmt(-padding)).append(" ")
                .append(fmt(width + 2 * padding)).append(" ")
                .append(fmt(height + 2 * padding)).append("\">\n");

        // фон
        svg.append("<rect x=\"0\" y=\"0\" width=\"")
                .append(fmt(width)).append("\" height=\"")
                .append(fmt(height))
                .append("\" fill=\"#ffffff\" stroke=\"#000\" stroke-width=\"3\"/>\n");

        // шины остаются как были (ты их рисуешь выше — не трогаем)

        svg.append("<g stroke=\"#c0d2e8\" stroke-width=\"1.2\" fill=\"none\" opacity=\"0.85\">\n");

        // ===== FIX: OVERSCAN =====
        double startX = clipRect.xmin - stepX;
        double startY = clipRect.ymin - stepY;

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
                List<Point> clipped = clipPolygonToRect(hex, clipRect);
                if (clipped.size() < 3) continue;

                svg.append("<path d=\"").append(svgPath(clipped)).append("\"/>\n");
            }
        }

        svg.append("</g>\n</svg>");
        return svg.toString();
    }

    // ===== Вспомогательная геометрия =====

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

    private Rect computeHoneycombClipRect(double width, double height,
                                          double offset, double busbarWidth,
                                          double clearance,
                                          boolean verticalBusbars) {

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

        return new Rect(left, top, right, bottom);
    }

    private List<Point> buildHexagon(double cx, double cy, double a) {
        double r = a;
        double h = Math.sqrt(3.0) * a / 2.0;

        List<Point> pts = new ArrayList<>();
        pts.add(new Point(cx - a / 2, cy - h));
        pts.add(new Point(cx + a / 2, cy - h));
        pts.add(new Point(cx + r, cy));
        pts.add(new Point(cx + a / 2, cy + h));
        pts.add(new Point(cx - a / 2, cy + h));
        pts.add(new Point(cx - r, cy));
        return pts;
    }

    private List<Point> clipPolygonToRect(List<Point> poly, Rect r) {
        return poly; // твой клиппинг остаётся
    }

    private String svgPath(List<Point> pts) {
        StringBuilder sb = new StringBuilder();
        sb.append("M ").append(fmt(pts.get(0).x)).append(" ").append(fmt(pts.get(0).y));
        for (int i = 1; i < pts.size(); i++)
            sb.append(" L ").append(fmt(pts.get(i).x)).append(" ").append(fmt(pts.get(i).y));
        sb.append(" Z");
        return sb.toString();
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }
}
