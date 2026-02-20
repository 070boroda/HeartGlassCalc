package by.greenmobile.heartglasscalc.service.physics;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static by.greenmobile.heartglasscalc.service.physics.ResistiveNetworkSolver.Segment;

/**
 * Генерация геометрии абляции "соты" в виде списка отрезков контуров.
 * Эти отрезки используются solver'ом для проверки: точка попадает в "вырез" (gap) или нет.
 *
 * ВАЖНО:
 * - Сегменты строятся по той же сетке/старту, что и SVG/DXF (через clip-область).
 * - "Зазор до шины" (busbarClearanceMm) ОБРАБАТЫВАЕТСЯ в solver'е: в зоне clearance абляция игнорируется.
 *   Поэтому здесь мы НЕ "съедаем ряд", а строим полную сетку, которая перекрывает clip-область.
 */
@Service
@Slf4j
public class HoneycombAblationGeometry {

    private static final class Rect {
        final double xmin, ymin, xmax, ymax;
        Rect(double xmin, double ymin, double xmax, double ymax) {
            this.xmin = xmin;
            this.ymin = ymin;
            this.xmax = xmax;
            this.ymax = ymax;
        }
        double width() { return Math.max(0.0, xmax - xmin); }
        double height() { return Math.max(0.0, ymax - ymin); }
    }

    public List<Segment> buildSegments(GlassParameters p) {
        List<Segment> segs = new ArrayList<>();
        if (p == null || !p.isHoneycomb()) return segs;

        Double aObj = p.getHexSide();
        Double gapObj = p.getHexGap();
        Integer colsObj = p.getHexCols();
        Integer rowsObj = p.getHexRows();

        if (aObj == null || aObj <= 0) return segs;
        if (colsObj == null || rowsObj == null) return segs;

        double a = aObj;
        double gap = (gapObj != null && gapObj >= 0) ? gapObj : 0.0;
        int cols = colsObj;
        int rows = rowsObj;

        double width = p.getWidth();
        double height = p.getHeight();
        double edge = Math.max(0.0, p.getEdgeOffset());
        double busW = Math.max(0.0, p.getBusbarWidth());
        boolean verticalBusbars = p.isVerticalBusbars();

        // Зазор до шины: если null -> gap (как в SVG/DXF/solver)
        double clearance = (p.getBusbarClearanceMm() != null)
                ? Math.max(0.0, p.getBusbarClearanceMm())
                : Math.max(0.0, gap);

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        Rect clip = computeHoneycombClipRect(width, height, edge, busW, clearance, verticalBusbars);
        if (clip.width() <= 0 || clip.height() <= 0) {
            log.warn("Honeycomb segments: clip-область пустая ({}x{}), segments=0", clip.width(), clip.height());
            return segs;
        }

        // Старт сетки центров — от clipRect (как после правки SVG/DXF)
        double startX = clip.xmin + a;
        double startY = clip.ymin + hexHeight / 2.0;

        int builtCells = 0;

        for (int col = 0; col < cols; col++) {
            double cx = startX + col * stepX;
            double colOffsetY = (col % 2 == 0) ? 0 : (stepY / 2.0);

            for (int row = 0; row < rows; row++) {
                double cy = startY + row * stepY + colOffsetY;

                // лёгкая проверка, чтобы не строить далеко вне clip
                if (cx + a < clip.xmin || cx - a > clip.xmax) continue;
                if (cy + hexHeight / 2.0 < clip.ymin || cy - hexHeight / 2.0 > clip.ymax) continue;

                addHexSegments(segs, cx, cy, a);
                builtCells++;
            }
        }

        log.debug("Honeycomb segments built: cells={}, segments={}", builtCells, segs.size());
        return segs;
    }

    private Rect computeHoneycombClipRect(double width, double height,
                                         double edge, double busW, double clearance,
                                         boolean verticalBusbars) {
        double left = edge;
        double right = width - edge;
        double top = edge;
        double bottom = height - edge;

        if (verticalBusbars) {
            top = edge + busW + clearance;
            bottom = height - edge - busW - clearance;
        } else {
            left = edge + busW + clearance;
            right = width - edge - busW - clearance;
        }

        if (right < left) right = left;
        if (bottom < top) bottom = top;

        return new Rect(left, top, right, bottom);
    }

    private void addHexSegments(List<Segment> segs, double cx, double cy, double a) {
        double r = a;
        double h = Math.sqrt(3.0) * a / 2.0;

        double x0 = cx - a / 2.0;
        double x1 = cx + a / 2.0;
        double xLeft = cx - r;
        double xRight = cx + r;
        double yTop = cy - h;
        double yBottom = cy + h;

        // 6 рёбер
        segs.add(new Segment(x0, yTop, x1, yTop));
        segs.add(new Segment(x1, yTop, xRight, cy));
        segs.add(new Segment(xRight, cy, x1, yBottom));
        segs.add(new Segment(x1, yBottom, x0, yBottom));
        segs.add(new Segment(x0, yBottom, xLeft, cy));
        segs.add(new Segment(xLeft, cy, x0, yTop));
    }
}
