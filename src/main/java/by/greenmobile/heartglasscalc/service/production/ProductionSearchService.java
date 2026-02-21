package by.greenmobile.heartglasscalc.service.production;

import by.greenmobile.heartglasscalc.entity.CandidateDesign;
import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import by.greenmobile.heartglasscalc.service.engine.HoneycombEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductionSearchService {
    private final ElectricalEngine electrical;
    private final HoneycombEstimator estimator;

    @Value("${production.topN:5}") private int topN;
    @Value("${production.tolerancePercent:10}") private double tolerancePercent;
    @Value("${production.autoExpand:true}") private boolean autoExpand;

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

        List<ScoredCandidate> baseFound = search(base, baseAMin, baseAMax, baseAStep, baseGapMin, baseGapMax, baseGapStep);
        List<ScoredCandidate> accepted = filterByTolerance(baseFound, tolerancePercent);
        if (!accepted.isEmpty()) return toTop(accepted, topN);

        if (!autoExpand) return toTop(baseFound, topN);

        log.info("AUTO: nothing in base range, expanding...");
        List<ScoredCandidate> extFound = search(base, extAMin, extAMax, extAStep, extGapMin, extGapMax, extGapStep);
        List<ScoredCandidate> extAccepted = filterByTolerance(extFound, tolerancePercent);
        if (!extAccepted.isEmpty()) return toTop(extAccepted, topN);

        return toTop(extFound, topN);
    }

    private List<ScoredCandidate> search(GlassParameters base,
                                         double aMin, double aMax, double aStep,
                                         double gMin, double gMax, double gStep) {

        // ВАЖНО: удельная мощность (Вт/м²) — по активной зоне между шинами
        double areaM2 = electrical.computeActiveAreaM2(base);
        double rawR = electrical.computeRawResistance(base);

        List<ScoredCandidate> out = new ArrayList<>();

        for (double a = aMin; a <= aMax + 1e-9; a += aStep) {
            for (double gap = gMin; gap <= gMax + 1e-9; gap += gStep) {
                double mult = estimator.estimateMultiplier(base, a, gap);
                if (mult <= 0) continue;

                double rAch = rawR * mult;
                if (rAch <= 0) continue;

                double pWm2 = electrical.computePowerWm2(rAch, areaM2);

                double dev = 0.0;
                if (base.getTargetPower() != null && base.getTargetPower() > 0) {
                    dev = (pWm2 - base.getTargetPower()) / base.getTargetPower() * 100.0;
                }

                // ранжирование "технологичности"
                double ablIntensity = gap / a;

                // оценка плотности ячеек
                double hexH = Math.sqrt(3.0) * a;
                double stepX = 1.5 * a + gap;
                double stepY = hexH + gap;
                double cellDensity = (stepX > 0 && stepY > 0) ? (1.0 / (stepX * stepY)) : Double.POSITIVE_INFINITY;

                CandidateDesign cd = new CandidateDesign(a, gap, mult, rAch, pWm2, dev);
                out.add(new ScoredCandidate(cd, ablIntensity, cellDensity));
            }
        }

        out.sort(Comparator
                .comparingDouble((ScoredCandidate s) -> Math.abs(s.design.getDeviationPercent()))
                .thenComparingDouble(s -> s.ablIntensity)
                .thenComparingDouble(s -> s.cellDensity)
        );

        return out;
    }

    private List<ScoredCandidate> filterByTolerance(List<ScoredCandidate> in, double tolPercent) {
        return in.stream()
                .filter(s -> Math.abs(s.design.getDeviationPercent()) <= tolPercent)
                .collect(Collectors.toList());
    }

    private List<CandidateDesign> toTop(List<ScoredCandidate> scored, int n) {
        return scored.stream()
                .limit(Math.max(1, n))
                .map(s -> s.design)
                .collect(Collectors.toList());
    }

    public MaxAchievable estimateMaxAchievable(GlassParameters base) {
        base.setPatternType(2);

        double areaM2 = electrical.computeActiveAreaM2(base);
        double rawR = electrical.computeRawResistance(base);

        double maxMult = 0.0;
        double maxPower = 0.0;
        double bestA = 0.0;
        double bestG = 0.0;

        for (double a = extAMin; a <= extAMax + 1e-9; a += extAStep) {
            for (double gap = extGapMin; gap <= extGapMax + 1e-9; gap += extGapStep) {
                double m = estimator.estimateMultiplier(base, a, gap);
                if (m > maxMult) {
                    maxMult = m;
                    double r = rawR * m;
                    double pWm2 = electrical.computePowerWm2(r, areaM2);
                    maxPower = pWm2;
                    bestA = a;
                    bestG = gap;
                }
            }
        }

        return new MaxAchievable(maxMult, maxPower, bestA, bestG);
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