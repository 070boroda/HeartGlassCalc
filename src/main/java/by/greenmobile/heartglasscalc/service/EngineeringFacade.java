package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.physics.ResistiveNetworkSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Единая точка инженерного расчёта:
 * - считает R_target, R_raw (без рисунка)
 * - генерирует геометрию (CalculationService)
 * - считает R_achieved физически (ResistiveNetworkSolver dx=2мм)
 * - считает мощность/удельную мощность и отклонение
 */
@Service
@Slf4j
public class EngineeringFacade {

    private static final double VOLTAGE = 220.0;

    private final CalculationService calculationService;
    private final ResistiveNetworkSolver solver;

    /**
     * LRU-кэш результатов solver — даёт огромный выигрыш при повторных/похожих запросах (особенно AUTO).
     * Ключ — только входные параметры, влияющие на solver.
     */
    private final LruCache<SolveKey, ResistiveNetworkSolver.SolveResult> solverCache = new LruCache<>(350);

    public EngineeringFacade(CalculationService calculationService,
                             ResistiveNetworkSolver solver) {
        this.calculationService = calculationService;
        this.solver = solver;
    }

    /**
     * ЕДИНСТВЕННЫЙ корректный расчёт: всегда через solver.
     */
    public GlassParameters solve(GlassParameters params) {
        // 1) базовый расчёт (R_target, multiplier-оценка, геометрия сот/зигзаг, энергетика)
        GlassParameters p = calculationService.calculate(params);

        if (p == null || !p.hasValidInput()) {
            return p;
        }

        // 2) Заполняем R_raw (без рисунка) честно
        double R_raw = computeRawResistance(p);
        p.setRawResistance(R_raw);

        // 3) R_target уже посчитан CalculationService как totalResistance
        double R_target = p.getTotalResistance() != null ? p.getTotalResistance() : computeTargetResistance(p);
        p.setTotalResistance(R_target);

        // 4) Физически считаем R_achieved solver'ом.
        // Для AUTO можно задавать p.solverMeshStepMm = 3..4 мм (быстрее),
        // для manual/финального — 2 мм (точнее).
        double meshStepMm = (p.getSolverMeshStepMm() != null && p.getSolverMeshStepMm() > 0)
                ? p.getSolverMeshStepMm()
                : 2.0;

        ResistiveNetworkSolver.SolveResult sr;
        SolveKey key = SolveKey.from(p, meshStepMm);
        sr = solverCache.get(key);
        if (sr == null) {
            sr = solver.solve(p, meshStepMm, 1.0); // U=1В => R=1/I
            solverCache.put(key, sr);
        }
        if (!sr.isOk()) {
            log.warn("SOLVER: {}", sr.getError());
            // fallback: базовую геометрию оставляем, но факт не заполняем
            p.setAchievedResistance(null);
            p.setAchievedPowerWatts(null);
            p.setAchievedPowerWm2(null);
            p.setPowerDeviationPercent(null);
            return p;
        }

        double R_ach = sr.getResistanceOhm();
        p.setAchievedResistance(R_ach);

        // 5) Фактический multiplier как отношение R_ach/R_raw
        double multFact = (R_raw > 0) ? (R_ach / R_raw) : 0.0;
        p.setPathLengthMultiplier(multFact);

        // 6) Фактическая мощность и удельная мощность
        double P_ach = (VOLTAGE * VOLTAGE) / R_ach;
        double areaM2 = computeArea(p);
        double P_ach_ud = (areaM2 > 0) ? (P_ach / areaM2) : 0.0;

        p.setAchievedPowerWatts(P_ach);
        p.setAchievedPowerWm2(P_ach_ud);

        // 7) отклонение от целевой удельной мощности
        double devPct = 0.0;
        if (p.getTargetPower() != null && p.getTargetPower() > 0) {
            devPct = 100.0 * (P_ach_ud - p.getTargetPower()) / p.getTargetPower();
        }
        p.setPowerDeviationPercent(devPct);

        log.info(
                "SOLVE: R_target={}, R_raw={}, multFact={}, R_ach={}, P_ach={}, devPct={}. " +
                        "SOLVER(mesh={}mm, nx={}, ny={}, segs={})",
                R_target, R_raw, multFact, R_ach, P_ach, devPct,
                sr.getMeshStepMm(), sr.getNx(), sr.getNy(), sr.getAblationSegmentsCount()
        );

        return p;
    }

    /**
     * Backward compatibility: старое имя метода.
     * Можешь удалить после того, как фронт и другие контроллеры перейдут на solve().
     */
    @Deprecated
    public GlassParameters manual(GlassParameters params) {
        return solve(params);
    }

    private double computeArea(GlassParameters p) {
        return (p.getWidth() * p.getHeight()) / 1_000_000.0;
    }

    private double computeTargetResistance(GlassParameters p) {
        double area = computeArea(p);
        if (area <= 0 || p.getTargetPower() == null || p.getTargetPower() <= 0) return 0.0;
        return (VOLTAGE * VOLTAGE) / (p.getTargetPower() * area);
    }

    private double computeRawResistance(GlassParameters p) {
        boolean verticalBusbars = p.isVerticalBusbars();
        double L = verticalBusbars ? p.getHeight() : p.getWidth();
        double W = verticalBusbars ? p.getWidth() : p.getHeight();
        return p.getSheetResistance() * (L / W);
    }

    // =====================================================================
    // Solver cache helpers
    // =====================================================================

    /**
     * Ключ solver: квантуем double до 1/1000 мм/Ом, чтобы ключ был стабильным.
     */
    private static final class SolveKey {
        final long w, h;
        final long rs;
        final long edge;
        final long busW;
        final int pattern;
        final int orient;
        final long clearance;
        final long a;
        final long gap;
        final int cols;
        final int rows;
        final long dx;

        private SolveKey(long w, long h, long rs, long edge, long busW, int pattern, int orient,
                         long clearance, long a, long gap, int cols, int rows, long dx) {
            this.w = w;
            this.h = h;
            this.rs = rs;
            this.edge = edge;
            this.busW = busW;
            this.pattern = pattern;
            this.orient = orient;
            this.clearance = clearance;
            this.a = a;
            this.gap = gap;
            this.cols = cols;
            this.rows = rows;
            this.dx = dx;
        }

        static SolveKey from(GlassParameters p, double meshStepMm) {
            Objects.requireNonNull(p);
            long w = q(p.getWidth());
            long h = q(p.getHeight());
            long rs = q(p.getSheetResistance());
            long edge = q(p.getEdgeOffset());
            long busW = q(p.getBusbarWidth());
            int pattern = p.getPatternType() != null ? p.getPatternType() : 0;
            int orient = p.getBusbarOrientation() != null ? p.getBusbarOrientation() : 1;
            long clearance = q(p.getBusbarClearanceMm() != null ? p.getBusbarClearanceMm() : 0.0);

            long a = q(p.getHexSide() != null ? p.getHexSide() : 0.0);
            long gap = q(p.getHexGap() != null ? p.getHexGap() : 0.0);
            int cols = p.getHexCols() != null ? p.getHexCols() : 0;
            int rows = p.getHexRows() != null ? p.getHexRows() : 0;
            long dx = q(meshStepMm);

            return new SolveKey(w, h, rs, edge, busW, pattern, orient, clearance, a, gap, cols, rows, dx);
        }

        private static long q(Double v) {
            if (v == null) return 0;
            return Math.round(v * 1000.0);
        }

        private static long q(double v) {
            return Math.round(v * 1000.0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SolveKey)) return false;
            SolveKey k = (SolveKey) o;
            return w == k.w && h == k.h && rs == k.rs && edge == k.edge && busW == k.busW
                    && pattern == k.pattern && orient == k.orient && clearance == k.clearance
                    && a == k.a && gap == k.gap && cols == k.cols && rows == k.rows && dx == k.dx;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(w);
            result = 31 * result + Long.hashCode(h);
            result = 31 * result + Long.hashCode(rs);
            result = 31 * result + Long.hashCode(edge);
            result = 31 * result + Long.hashCode(busW);
            result = 31 * result + pattern;
            result = 31 * result + orient;
            result = 31 * result + Long.hashCode(clearance);
            result = 31 * result + Long.hashCode(a);
            result = 31 * result + Long.hashCode(gap);
            result = 31 * result + cols;
            result = 31 * result + rows;
            result = 31 * result + Long.hashCode(dx);
            return result;
        }
    }

    /**
     * Очень простой LRU без внешних зависимостей.
     * synchronized достаточно, т.к. solve() может вызываться параллельно из AUTO.
     */
    private static final class LruCache<K, V> {
        private final int maxSize;
        private final LinkedHashMap<K, V> map;

        LruCache(int maxSize) {
            this.maxSize = Math.max(50, maxSize);
            this.map = new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > LruCache.this.maxSize;
                }
            };
        }

        synchronized V get(K key) {
            return map.get(key);
        }

        synchronized void put(K key, V value) {
            map.put(key, value);
        }
    }
}
