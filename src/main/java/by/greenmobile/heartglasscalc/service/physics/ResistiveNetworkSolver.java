package by.greenmobile.heartglasscalc.service.physics;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Физически корректный расчёт эквивалентного сопротивления тонкоплёночного покрытия с абляцией:
 * решаем ∇·(σ ∇V)=0 на прямоугольной сетке dx=dy=meshStepMm.
 *
 * - Покрытие: sheetResistance Rs (Ом/□) => проводимость на ребре g0 = 1/Rs (Сименс)
 * - Абляция: sigmaFactor ≈ 0 (очень маленькое), т.е. почти разрыв цепи
 * - Шины: идеальные электроды (Dirichlet) на прямоугольниках шин
 * - Край edgeOffset: покрытия нет (абляция), т.е. sigmaFactor ≈ 0
 *
 * Возвращаем R=U/I, где I - суммарный ток из "горячей" шины при приложенном U.
 */
@Service
@Slf4j
public class ResistiveNetworkSolver {

    // По умолчанию 2 мм как ты выбрал
    private static final double DEFAULT_DX_MM = 2.0;

    // "Почти разрыв" (чтобы матрица не была вырождена)
    private static final double SIGMA_ABLATION = 1e-6;

    // CG параметры
    private static final int CG_MAX_ITERS = 4000;
    private static final double CG_TOL = 1e-8;

    private final HoneycombAblationGeometry honeycombAblationGeometry;

    public ResistiveNetworkSolver(HoneycombAblationGeometry honeycombAblationGeometry) {
        this.honeycombAblationGeometry = honeycombAblationGeometry;
    }

    public SolveResult solve(GlassParameters p) {
        double dx = (p != null && p.getSolverMeshStepMm() != null && p.getSolverMeshStepMm() > 0)
                ? p.getSolverMeshStepMm()
                : DEFAULT_DX_MM;
        return solve(p, dx, 1.0); // U=1В удобно => R=1/I
    }

    /**
     * @param meshStepMm шаг сетки (dx=dy)
     * @param voltageV   приложенное напряжение (можно 1В)
     */
    public SolveResult solve(GlassParameters p, double meshStepMm, double voltageV) {
        Objects.requireNonNull(p, "params");

        if (p.getWidth() <= 0 || p.getHeight() <= 0 || p.getSheetResistance() <= 0) {
            return SolveResult.invalid("Некорректные входные параметры (width/height/Rs)");
        }

        double dx = meshStepMm;
        int nx = (int) Math.ceil(p.getWidth() / dx) + 1;   // узлы по X
        int ny = (int) Math.ceil(p.getHeight() / dx) + 1;  // узлы по Y

        if (nx < 3 || ny < 3) {
            return SolveResult.invalid("Сетка слишком грубая/маленькая");
        }

        // Базовая проводимость ребра для "квадрата" dx=dy:
        // R_edge = Rs * (dx/dy) = Rs, значит g0 = 1/Rs
        final double g0 = 1.0 / p.getSheetResistance();

        // Геометрия сот: список отрезков абляции (контуры)
        final List<Segment> ablationSegments = p.isHoneycomb()
                ? honeycombAblationGeometry.buildSegments(p)
                : Collections.emptyList();

        // Ширина абляции: берём gap (мм)
        final double gap = (p.getHexGap() != null && p.getHexGap() > 0) ? p.getHexGap() : 0.0;
        final double halfGap = gap / 2.0;

        // Зазор до шины (busbarClearanceMm) — если null, используем gap
        final double clearance = (p.getBusbarClearanceMm() != null)
                ? Math.max(0.0, p.getBusbarClearanceMm())
                : Math.max(0.0, gap);

        // Ускорение: строим spatial-index для сегментов, чтобы не гонять по всем сегментам на каждую точку.
        // На типичных размерах это убирает сотни миллионов вычислений distance-to-segment.
        final SegmentIndex segmentIndex = (p.isHoneycomb() && gap > 0 && !ablationSegments.isEmpty())
                ? SegmentIndex.build(ablationSegments, halfGap)
                : null;

        // Маска: sigmaFactor в каждом узле (1 = покрытие, ~0 = абляция/снятие)
        double[][] sigma = new double[nx][ny];

        // Предрасчёт: зоны электродов (Dirichlet)
        boolean[][] isDirichlet = new boolean[nx][ny];
        double[][] dirichletV = new double[nx][ny];

        // Границы "рабочей зоны" по покрытию (edgeOffset)
        double edge = Math.max(0.0, p.getEdgeOffset());

        // Параметры шин
        double busW = Math.max(0.0, p.getBusbarWidth());
        boolean verticalBusbars = p.isVerticalBusbars(); // true = сверху/снизу, false = слева/справа

        // Обозначим электроды:
        // - "горячая" шина: V=U
        // - "холодная" шина: V=0
        for (int ix = 0; ix < nx; ix++) {
            double x = ix * dx;
            for (int iy = 0; iy < ny; iy++) {
                double y = iy * dx;

                // 1) edgeOffset: покрытия нет
                boolean inRemovedEdge = (x < edge) || (x > p.getWidth() - edge) || (y < edge) || (y > p.getHeight() - edge);
                if (inRemovedEdge) {
                    sigma[ix][iy] = SIGMA_ABLATION;
                    continue;
                }

                // 2) Шины — считаем покрытие есть, но задаём потенциал
                if (verticalBusbars) {
                    // верхняя шина: прямоугольник внутри edgeOffset
                    boolean inTopBus = (y >= edge) && (y <= edge + busW) && (x >= edge) && (x <= p.getWidth() - edge);
                    boolean inBotBus = (y >= p.getHeight() - edge - busW) && (y <= p.getHeight() - edge) && (x >= edge) && (x <= p.getWidth() - edge);
                    if (inTopBus) {
                        isDirichlet[ix][iy] = true;
                        dirichletV[ix][iy] = voltageV;
                        sigma[ix][iy] = 1.0;
                        continue;
                    }
                    if (inBotBus) {
                        isDirichlet[ix][iy] = true;
                        dirichletV[ix][iy] = 0.0;
                        sigma[ix][iy] = 1.0;
                        continue;
                    }
                } else {
                    boolean inLeftBus = (x >= edge) && (x <= edge + busW) && (y >= edge) && (y <= p.getHeight() - edge);
                    boolean inRightBus = (x >= p.getWidth() - edge - busW) && (x <= p.getWidth() - edge) && (y >= edge) && (y <= p.getHeight() - edge);
                    if (inLeftBus) {
                        isDirichlet[ix][iy] = true;
                        dirichletV[ix][iy] = voltageV;
                        sigma[ix][iy] = 1.0;
                        continue;
                    }
                    if (inRightBus) {
                        isDirichlet[ix][iy] = true;
                        dirichletV[ix][iy] = 0.0;
                        sigma[ix][iy] = 1.0;
                        continue;
                    }
                }

                // 3) Абляция сот: если точка близко к отрезку контура (gap/2) => "вырез"
                // НО: рядом с шиной оставляем "полоску" clearance: там абляцию игнорируем
                if (p.isHoneycomb() && gap > 0) {
                    if (isInsideBusbarClearanceZone(p, x, y, clearance)) {
                        sigma[ix][iy] = 1.0;
                    } else {
                        boolean isAbl = (segmentIndex != null)
                                ? segmentIndex.isNearAny(x, y)
                                : isNearAnySegment(x, y, ablationSegments, halfGap);
                        sigma[ix][iy] = isAbl ? SIGMA_ABLATION : 1.0;
                    }
                } else {
                    sigma[ix][iy] = 1.0;
                }
            }
        }

        // Построим отображение (ix,iy) -> индекс неизвестного в векторе
        int[][] id = new int[nx][ny];
        Arrays.stream(id).forEach(a -> Arrays.fill(a, -1));

        int unknownCount = 0;
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                if (!isDirichlet[ix][iy]) {
                    // Узел может быть почти изолирован (sigma ~ 0), но всё равно включаем,
                    // CG сам справится, а SIGMA_ABLATION предотвращает вырождение.
                    id[ix][iy] = unknownCount++;
                }
            }
        }

        if (unknownCount == 0) {
            return SolveResult.invalid("Нет неизвестных узлов (вся область стала электродом?)");
        }

        // Собираем sparse матрицу A и правую часть b:
        // для узла i: sum(g_ij*(V_i - V_j)) = 0
        // => Aii += sum(g_ij), Aij -= g_ij
        // если сосед Dirichlet => b += g_ij * V_dir
        SparseMatrix A = new SparseMatrix(unknownCount);
        double[] b = new double[unknownCount];

        int[][] neigh = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};

        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                if (isDirichlet[ix][iy]) continue;

                int row = id[ix][iy];
                double sHere = sigma[ix][iy];

                double diag = 0.0;

                for (int[] d : neigh) {
                    int jx = ix + d[0];
                    int jy = iy + d[1];
                    if (jx < 0 || jx >= nx || jy < 0 || jy >= ny) continue;

                    double sThere = sigma[jx][jy];
                    // Средняя проводимость на ребре (грубая, но устойчивая)
                    double sEdge = 0.5 * (sHere + sThere);

                    // Если обе стороны почти "вырез", ребро почти не проводит
                    double g = g0 * sEdge;

                    if (g <= 0) continue;

                    diag += g;

                    if (isDirichlet[jx][jy]) {
                        b[row] += g * dirichletV[jx][jy];
                    } else {
                        int col = id[jx][jy];
                        A.add(row, col, -g);
                    }
                }

                // Диагональ
                A.add(row, row, diag);
            }
        }

        // Решаем A x = b методом CG.
        // Для более грубой сетки (AUTO) можно смягчить критерии — это ускоряет в разы.
        int maxIters = (dx >= 4.0) ? 1500 : CG_MAX_ITERS;
        double tol = (dx >= 4.0) ? 3e-8 : CG_TOL;
        double[] x = conjugateGradient(A, b, maxIters, tol);

        // Восстановим напряжения
        double[][] V = new double[nx][ny];
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                if (isDirichlet[ix][iy]) {
                    V[ix][iy] = dirichletV[ix][iy];
                } else {
                    V[ix][iy] = x[id[ix][iy]];
                }
            }
        }

        // Считаем суммарный ток из "горячей" шины (там где Dirichlet=U)
        double Itotal = 0.0;
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                if (!isDirichlet[ix][iy]) continue;
                if (Math.abs(dirichletV[ix][iy] - voltageV) > 1e-12) continue; // только "горячая"

                double sHere = sigma[ix][iy];
                // ток уходит в соседние узлы
                for (int[] d : neigh) {
                    int jx = ix + d[0];
                    int jy = iy + d[1];
                    if (jx < 0 || jx >= nx || jy < 0 || jy >= ny) continue;

                    double sThere = sigma[jx][jy];
                    double sEdge = 0.5 * (sHere + sThere);
                    double g = g0 * sEdge;
                    if (g <= 0) continue;

                    double dV = V[ix][iy] - V[jx][jy];
                    if (dV > 0) {
                        Itotal += g * dV;
                    }
                }
            }
        }

        if (Itotal <= 0) {
            return SolveResult.invalid("Ток не течёт (Itotal<=0). Проверь геометрию/зоны изоляции.");
        }

        double R = voltageV / Itotal;

        SolveResult r = SolveResult.ok(R, Itotal, dx, nx, ny);
        r.setAblationSegmentsCount(ablationSegments.size());
        return r;
    }

    private boolean isInsideBusbarClearanceZone(GlassParameters p, double x, double y, double clearance) {
        if (clearance <= 0) return false;

        double edge = Math.max(0.0, p.getEdgeOffset());
        double busW = Math.max(0.0, p.getBusbarWidth());
        boolean verticalBusbars = p.isVerticalBusbars();

        if (verticalBusbars) {
            // clearance зона под верхней шиной и над нижней шиной
            double topLimit = edge + busW + clearance;
            double botLimit = p.getHeight() - edge - busW - clearance;
            return (y <= topLimit) || (y >= botLimit);
        } else {
            double leftLimit = edge + busW + clearance;
            double rightLimit = p.getWidth() - edge - busW - clearance;
            return (x <= leftLimit) || (x >= rightLimit);
        }
    }

    private boolean isNearAnySegment(double x, double y, List<Segment> segs, double threshold) {
        double thr2 = threshold * threshold;
        for (Segment s : segs) {
            // быстрый bbox reject
            double minX = Math.min(s.x1, s.x2) - threshold;
            double maxX = Math.max(s.x1, s.x2) + threshold;
            double minY = Math.min(s.y1, s.y2) - threshold;
            double maxY = Math.max(s.y1, s.y2) + threshold;
            if (x < minX || x > maxX || y < minY || y > maxY) continue;
            if (distancePointToSegmentSquared(x, y, s.x1, s.y1, s.x2, s.y2) <= thr2) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Spatial index for segments
    // =====================================================================

    /**
     * Простая spatial-hash индексация сегментов.
     * Делит плоскость на клетки cellSizeMm и хранит сегменты по клеткам.
     */
    private static final class SegmentIndex {
        private final double cellSizeMm;
        private final double threshold;
        private final double thr2;
        private final Map<Long, List<SegmentBB>> buckets;

        private SegmentIndex(double cellSizeMm, double threshold, Map<Long, List<SegmentBB>> buckets) {
            this.cellSizeMm = cellSizeMm;
            this.threshold = threshold;
            this.thr2 = threshold * threshold;
            this.buckets = buckets;
        }

        static SegmentIndex build(List<Segment> segs, double threshold) {
            // размер клетки: чем больше, тем меньше бакетов, но больше сегментов в каждом.
            // Хороший компромисс: 25..35 мм, либо 4*threshold.
            double cell = Math.max(25.0, threshold * 4.0);

            Map<Long, List<SegmentBB>> map = new HashMap<>(segs.size() * 2);
            for (Segment s : segs) {
                SegmentBB bb = new SegmentBB(s, threshold);

                int cx0 = (int) Math.floor(bb.minX / cell);
                int cx1 = (int) Math.floor(bb.maxX / cell);
                int cy0 = (int) Math.floor(bb.minY / cell);
                int cy1 = (int) Math.floor(bb.maxY / cell);

                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cy = cy0; cy <= cy1; cy++) {
                        long key = key(cx, cy);
                        map.computeIfAbsent(key, k -> new ArrayList<>(8)).add(bb);
                    }
                }
            }
            return new SegmentIndex(cell, threshold, map);
        }

        boolean isNearAny(double x, double y) {
            int cx = (int) Math.floor(x / cellSizeMm);
            int cy = (int) Math.floor(y / cellSizeMm);

            // проверяем текущую и соседние клетки (3x3)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<SegmentBB> list = buckets.get(key(cx + dx, cy + dy));
                    if (list == null) continue;
                    for (SegmentBB bb : list) {
                        if (x < bb.minX || x > bb.maxX || y < bb.minY || y > bb.maxY) continue;
                        Segment s = bb.s;
                        if (distancePointToSegmentSquaredStatic(x, y, s.x1, s.y1, s.x2, s.y2) <= thr2) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static long key(int cx, int cy) {
            return (((long) cx) << 32) ^ (cy & 0xffffffffL);
        }

        private static final class SegmentBB {
            final Segment s;
            final double minX, maxX, minY, maxY;

            SegmentBB(Segment s, double threshold) {
                this.s = s;
                this.minX = Math.min(s.x1, s.x2) - threshold;
                this.maxX = Math.max(s.x1, s.x2) + threshold;
                this.minY = Math.min(s.y1, s.y2) - threshold;
                this.maxY = Math.max(s.y1, s.y2) + threshold;
            }
        }
    }

    // Статическая версия, чтобы SegmentIndex был static без доступа к this
    private static double distancePointToSegmentSquaredStatic(double px, double py, double x1, double y1, double x2, double y2) {
        double vx = x2 - x1;
        double vy = y2 - y1;
        double wx = px - x1;
        double wy = py - y1;

        double c1 = vx * wx + vy * wy;
        if (c1 <= 0) return (px - x1) * (px - x1) + (py - y1) * (py - y1);

        double c2 = vx * vx + vy * vy;
        if (c2 <= c1) return (px - x2) * (px - x2) + (py - y2) * (py - y2);

        double t = c1 / c2;
        double projx = x1 + t * vx;
        double projy = y1 + t * vy;

        double dx = px - projx;
        double dy = py - projy;
        return dx * dx + dy * dy;
    }

    // Квадрат расстояния от точки до отрезка
    private double distancePointToSegmentSquared(double px, double py, double x1, double y1, double x2, double y2) {
        double vx = x2 - x1;
        double vy = y2 - y1;
        double wx = px - x1;
        double wy = py - y1;

        double c1 = vx * wx + vy * wy;
        if (c1 <= 0) return (px - x1) * (px - x1) + (py - y1) * (py - y1);

        double c2 = vx * vx + vy * vy;
        if (c2 <= c1) return (px - x2) * (px - x2) + (py - y2) * (py - y2);

        double t = c1 / c2;
        double projx = x1 + t * vx;
        double projy = y1 + t * vy;

        double dx = px - projx;
        double dy = py - projy;
        return dx * dx + dy * dy;
    }

    // =========================
    // CG solver for SparseMatrix
    // =========================
    private double[] conjugateGradient(SparseMatrix A, double[] b, int maxIters, double tol) {
        int n = b.length;
        double[] x = new double[n];
        double[] r = Arrays.copyOf(b, n); // x=0 => r=b
        double[] p = Arrays.copyOf(r, n);
        double[] Ap = new double[n];

        double rsOld = dot(r, r);
        if (Math.sqrt(rsOld) < tol) return x;

        for (int it = 0; it < maxIters; it++) {
            A.mul(p, Ap);

            double alpha = rsOld / Math.max(1e-30, dot(p, Ap));

            axpy(x, p, alpha);      // x += alpha p
            axpy(r, Ap, -alpha);    // r -= alpha Ap

            double rsNew = dot(r, r);
            if (Math.sqrt(rsNew) < tol) {
                log.debug("CG converged in {} iterations, resid={}", it + 1, Math.sqrt(rsNew));
                return x;
            }

            double beta = rsNew / Math.max(1e-30, rsOld);

            // p = r + beta p
            for (int i = 0; i < n; i++) {
                p[i] = r[i] + beta * p[i];
            }
            rsOld = rsNew;
        }

        log.warn("CG reached maxIters={}, resid={}", maxIters, Math.sqrt(rsOld));
        return x;
    }

    private double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private void axpy(double[] y, double[] x, double a) {
        for (int i = 0; i < y.length; i++) y[i] += a * x[i];
    }

    // =========================
    // Helper types
    // =========================

    public static class Segment {
        public final double x1, y1, x2, y2;
        public Segment(double x1, double y1, double x2, double y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }

    public static class SolveResult {
        private boolean ok;
        private String error;

        private double resistanceOhm;
        private double totalCurrentA;
        private double meshStepMm;
        private int nx, ny;
        private int ablationSegmentsCount;

        public static SolveResult ok(double r, double i, double dx, int nx, int ny) {
            SolveResult s = new SolveResult();
            s.ok = true;
            s.resistanceOhm = r;
            s.totalCurrentA = i;
            s.meshStepMm = dx;
            s.nx = nx;
            s.ny = ny;
            return s;
        }

        public static SolveResult invalid(String msg) {
            SolveResult s = new SolveResult();
            s.ok = false;
            s.error = msg;
            return s;
        }

        public boolean isOk() { return ok; }
        public String getError() { return error; }
        public double getResistanceOhm() { return resistanceOhm; }
        public double getTotalCurrentA() { return totalCurrentA; }
        public double getMeshStepMm() { return meshStepMm; }
        public int getNx() { return nx; }
        public int getNy() { return ny; }

        public int getAblationSegmentsCount() { return ablationSegmentsCount; }
        public void setAblationSegmentsCount(int c) { this.ablationSegmentsCount = c; }
    }

    /**
     * Очень простой CSR-подобный sparse: map по строкам.
     * Для наших размеров и CG этого достаточно, без внешних либ.
     */
    private static class SparseMatrix {
        private final int n;
        private final Map<Integer, Double>[] rows;

        @SuppressWarnings("unchecked")
        SparseMatrix(int n) {
            this.n = n;
            this.rows = new Map[n];
            for (int i = 0; i < n; i++) rows[i] = new HashMap<>();
        }

        void add(int r, int c, double v) {
            Map<Integer, Double> row = rows[r];
            row.merge(c, v, Double::sum);
        }

        void mul(double[] x, double[] out) {
            Arrays.fill(out, 0.0);
            for (int r = 0; r < n; r++) {
                double sum = 0.0;
                for (Map.Entry<Integer, Double> e : rows[r].entrySet()) {
                    sum += e.getValue() * x[e.getKey()];
                }
                out[r] = sum;
            }
        }
    }
}
