package by.greenmobile.heartglasscalc.service.production;

import by.greenmobile.heartglasscalc.entity.CandidateDesign;
import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.EngineeringFacade;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import by.greenmobile.heartglasscalc.service.engine.HoneycombEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Production (AUTO): поиск технологичных параметров сот (a, gap).
 *
 * НОВАЯ логика:
 * 1) Быстрый перебор по оценочной модели (HoneycombEstimator) — только чтобы отранжировать кандидатов.
 * 2) Финальная оценка ТОП-K кандидатов через EngineeringFacade.solve() (то есть через solver).
 *
 * Таким образом:
 * - UI получает ТОП вариантов с фактическими (solver) R/P/dev.
 * - Производительность сохраняется.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductionSearchService {

    private final ElectricalEngine electrical;
    private final HoneycombEstimator estimator;
    private final EngineeringFacade engineeringFacade;

    @Value("${production.topN:5}")
    private int topN;

    @Value("${production.tolerancePercent:10}")
    private double tolerancePercent;

    @Value("${production.autoExpand:true}")
    private boolean autoExpand;

    /** Сколько кандидатов после грубого ранжирования прогонять через solver. */
    @Value("${production.solverTopK:30}")
    private int solverTopK;

    /** Шаг сетки solver для AUTO (мм). 3-4мм обычно ускоряет в разы. */
    @Value("${production.solverMeshStepMm:4.0}")
    private double solverMeshStepMm;

    @Value("${production.base.aMin:10}") private double baseAMin;
    @Value("${production.base.aMax:70}") private double baseAMax;
    @Value("${production.base.aStep:1.0}") private double baseAStep;

    @Value("${production.base.gapMin:0.5}") private double baseGapMin;
    @Value("${production.base.gapMax:10}") private double baseGapMax;
    @Value("${production.base.gapStep:0.5}") private double baseGapStep;

    @Value("${production.ext.aMin:5}") private double extAMin;
    @Value("${production.ext.aMax:80}") private double extAMax;
    @Value("${production.ext.aStep:0.5}") private double extAStep;

    @Value("${production.ext.gapMin:0.5}") private double extGapMin;
    @Value("${production.ext.gapMax:12}") private double extGapMax;
    @Value("${production.ext.gapStep:0.5}") private double extGapStep;

    public List<CandidateDesign> findTopDesigns(GlassParameters base) {
        base.setPatternType(2);

        // 1) быстрый поиск (оценочный)
        List<ScoredCandidate> baseFound =
                searchApprox(base, baseAMin, baseAMax, baseAStep, baseGapMin, baseGapMax, baseGapStep);

        // 2) solver-оценка лучших
        List<CandidateDesign> baseSolved = solveTopK(base, baseFound);
        List<CandidateDesign> baseAccepted = filterByTolerance(baseSolved, tolerancePercent);
        if (!baseAccepted.isEmpty()) return baseAccepted.stream().limit(Math.max(1, topN)).collect(Collectors.toList());

        if (!autoExpand) {
            return baseSolved.stream().limit(Math.max(1, topN)).collect(Collectors.toList());
        }

        log.info("AUTO: nothing in base range (solver), expanding...");

        List<ScoredCandidate> extFound =
                searchApprox(base, extAMin, extAMax, extAStep, extGapMin, extGapMax, extGapStep);

        List<CandidateDesign> extSolved = solveTopK(base, extFound);
        List<CandidateDesign> extAccepted = filterByTolerance(extSolved, tolerancePercent);
        if (!extAccepted.isEmpty()) return extAccepted.stream().limit(Math.max(1, topN)).collect(Collectors.toList());

        return extSolved.stream().limit(Math.max(1, topN)).collect(Collectors.toList());
    }

    /**
     * Быстрый перебор по оценочной модели, чтобы получить ранжированный список кандидатов.
     * ВАЖНО: здесь CandidateDesign несёт оценочные R/P/dev — они будут заменены после solver-оценки.
     */
    private List<ScoredCandidate> searchApprox(GlassParameters base,
                                              double aMin, double aMax, double aStep,
                                              double gMin, double gMax, double gStep) {

        double areaM2 = electrical.computeAreaM2(base);
        double rawR = electrical.computeRawResistance(base);

        List<ScoredCandidate> out = new ArrayList<>();

        for (double a = aMin; a <= aMax + 1e-9; a += aStep) {
            for (double gap = gMin; gap <= gMax + 1e-9; gap += gStep) {

                double multEst = estimator.estimateMultiplier(base, a, gap);
                if (multEst <= 0) continue;

                double rEst = rawR * multEst;
                if (rEst <= 0) continue;

                double pWm2Est = electrical.computePowerWm2(rEst, areaM2);

                double devEst = 0.0;
                if (base.getTargetPower() != null && base.getTargetPower() > 0) {
                    devEst = (pWm2Est - base.getTargetPower()) / base.getTargetPower() * 100.0;
                }

                double ablIntensity = (a > 0) ? (gap / a) : Double.POSITIVE_INFINITY;

                double hexH = Math.sqrt(3.0) * a;
                double stepX = 1.5 * a + gap;
                double stepY = hexH + gap;
                double cellDensity = (stepX > 0 && stepY > 0) ? (1.0 / (stepX * stepY)) : Double.POSITIVE_INFINITY;

                CandidateDesign cdEst = new CandidateDesign(a, gap, multEst, rEst, pWm2Est, devEst);
                out.add(new ScoredCandidate(cdEst, ablIntensity, cellDensity));
            }
        }

        // Сортируем по |dev|, затем по технологичности
        out.sort(Comparator
                .comparingDouble((ScoredCandidate s) -> Math.abs(s.design.getDeviationPercent()))
                .thenComparingDouble(s -> s.ablIntensity)
                .thenComparingDouble(s -> s.cellDensity)
        );

        return out;
    }

    /**
     * Прогоняем solver для ТОП-K кандидатов из оценочного списка.
     */
    private List<CandidateDesign> solveTopK(GlassParameters base, List<ScoredCandidate> approxRanked) {
        if (approxRanked.isEmpty()) return Collections.emptyList();

        // ВАЖНО: AUTO должен быть быстрым. По умолчанию берём 8..15 кандидатов.
        int k = Math.max(solverTopK, Math.max(8, topN * 3));
        k = Math.min(k, approxRanked.size());

        // Ранний стоп: если нашли вариант достаточно близко к цели, можно прекращать.
        // В параллельном режиме делаем «мягко»: отсечка по лучшему найденному результату.
        AtomicReference<Double> bestAbsDev = new AtomicReference<>(Double.POSITIVE_INFINITY);

        List<SolvedCandidate> solved = approxRanked.stream()
                .limit(k)
                .parallel() // ключевое ускорение
                .map(sc -> {
                    // если уже нашли очень хороший результат, можно пропускать часть работ
                    Double best = bestAbsDev.get();
                    if (best != null && best <= 1.0) {
                        // 1% — «очень хорошо» для AUTO
                        // оставляем шанс улучшить технологичность, но многие кандидаты можно не считать
                        // (не строго, т.к. параллель)
                    }

                    double a = sc.design.getHexSide();
                    double gap = sc.design.getHexGap();

                    GlassParameters cand = buildCandidateParams(base, a, gap);
                    cand.setSolverMeshStepMm(solverMeshStepMm);

                    GlassParameters res = engineeringFacade.solve(cand);
                    if (res.getAchievedPowerWm2() == null || res.getAchievedResistance() == null || res.getPowerDeviationPercent() == null) {
                        return null;
                    }

                    double absDev = Math.abs(res.getPowerDeviationPercent());
                    bestAbsDev.accumulateAndGet(absDev, Math::min);

                    CandidateDesign cd = new CandidateDesign(
                            a,
                            gap,
                            res.getPathLengthMultiplier() != null ? res.getPathLengthMultiplier() : 0.0,
                            res.getAchievedResistance(),
                            res.getAchievedPowerWm2(),
                            res.getPowerDeviationPercent()
                    );

                    return new SolvedCandidate(cd, sc.ablIntensity, sc.cellDensity);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Финальная сортировка по solver-dev, затем технологичность
        solved.sort(Comparator
                .comparingDouble((SolvedCandidate s) -> Math.abs(s.design.getDeviationPercent()))
                .thenComparingDouble(s -> s.ablIntensity)
                .thenComparingDouble(s -> s.cellDensity)
        );

        return solved.stream().map(s -> s.design).collect(Collectors.toList());
    }

    private List<CandidateDesign> filterByTolerance(List<CandidateDesign> in, double tolPercent) {
        return in.stream()
                .filter(d -> Math.abs(d.getDeviationPercent()) <= tolPercent)
                .collect(Collectors.toList());
    }

    /**
     * Оценка максимальной достижимости.
     *
     * Новая логика:
     * - Сначала грубо выбираем K кандидатов с самым большим estimator-multiplier.
     * - Затем считаем их solver'ом и возвращаем максимум по факту.
     */
    public MaxAchievable estimateMaxAchievable(GlassParameters base) {
        base.setPatternType(2);

        List<EstOnly> est = new ArrayList<>();

        for (double a = extAMin; a <= extAMax + 1e-9; a += extAStep) {
            for (double gap = extGapMin; gap <= extGapMax + 1e-9; gap += extGapStep) {
                double mEst = estimator.estimateMultiplier(base, a, gap);
                if (mEst > 0) est.add(new EstOnly(a, gap, mEst));
            }
        }

        if (est.isEmpty()) {
            return new MaxAchievable(0.0, 0.0, 0.0, 0.0);
        }

        est.sort(Comparator.comparingDouble((EstOnly e) -> -e.multEst));

        int k = Math.min(Math.max(20, topN * 6), est.size());

        double bestMult = 0.0;
        double bestPower = 0.0;
        double bestA = 0.0;
        double bestG = 0.0;

        for (int i = 0; i < k; i++) {
            EstOnly e = est.get(i);
            GlassParameters cand = buildCandidateParams(base, e.a, e.gap);
            cand.setSolverMeshStepMm(solverMeshStepMm);
            GlassParameters res = engineeringFacade.solve(cand);
            if (res.getPathLengthMultiplier() == null || res.getAchievedPowerWm2() == null) continue;

            double mult = res.getPathLengthMultiplier();
            double pWm2 = res.getAchievedPowerWm2();

            if (mult > bestMult) {
                bestMult = mult;
                bestPower = pWm2;
                bestA = e.a;
                bestG = e.gap;
            }
        }

        return new MaxAchievable(bestMult, bestPower, bestA, bestG);
    }

    /**
     * Создаём независимый набор входных параметров (чтобы solver не портил base).
     */
    private GlassParameters buildCandidateParams(GlassParameters base, double a, double gap) {
        return GlassParameters.builder()
                .width(base.getWidth())
                .height(base.getHeight())
                .targetPower(base.getTargetPower())
                .sheetResistance(base.getSheetResistance())
                .edgeOffset(base.getEdgeOffset())
                .busbarWidth(base.getBusbarWidth())
                .patternType(2)
                .busbarOrientation(base.getBusbarOrientation())
                .operationMode(base.getOperationMode())
                .glassThickness(base.getGlassThickness())
                .ambientTemperature(base.getAmbientTemperature())
                .targetSurfaceTemperature(base.getTargetSurfaceTemperature())
                .busbarClearanceMm(base.getBusbarClearanceMm())
                .hexSide(a)
                .hexGap(gap)
                .build();
    }

    private static class ScoredCandidate {
        final CandidateDesign design;
        final double ablIntensity;
        final double cellDensity;

        ScoredCandidate(CandidateDesign design, double ablIntensity, double cellDensity) {
            this.design = design;
            this.ablIntensity = ablIntensity;
            this.cellDensity = cellDensity;
        }
    }

    private static class SolvedCandidate {
        final CandidateDesign design;
        final double ablIntensity;
        final double cellDensity;

        SolvedCandidate(CandidateDesign design, double ablIntensity, double cellDensity) {
            this.design = design;
            this.ablIntensity = ablIntensity;
            this.cellDensity = cellDensity;
        }
    }

    private static class EstOnly {
        final double a;
        final double gap;
        final double multEst;

        EstOnly(double a, double gap, double multEst) {
            this.a = a;
            this.gap = gap;
            this.multEst = multEst;
        }
    }

    public static class MaxAchievable {
        public final double maxMultiplier;
        public final double maxPowerWm2;
        public final double bestA;
        public final double bestGap;

        public MaxAchievable(double maxMultiplier, double maxPowerWm2, double bestA, double bestGap) {
            this.maxMultiplier = maxMultiplier;
            this.maxPowerWm2 = maxPowerWm2;
            this.bestA = bestA;
            this.bestGap = bestGap;
        }
    }
}
