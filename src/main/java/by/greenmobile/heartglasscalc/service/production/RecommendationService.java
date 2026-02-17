package by.greenmobile.heartglasscalc.service.production;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import by.greenmobile.heartglasscalc.service.engine.HoneycombEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final ElectricalEngine electrical;
    private final HoneycombEstimator estimator;
    private final ProductionSearchService productionSearchService;

    @Value("${production.ext.aMin:5}") private double aMin;
    @Value("${production.ext.aMax:80}") private double aMax;
    @Value("${production.ext.aStep:0.5}") private double aStep;

    @Value("${production.ext.gapMin:0.5}") private double gapMin;
    @Value("${production.ext.gapMax:12}") private double gapMax;
    @Value("${production.ext.gapStep:0.5}") private double gapStep;

    public String generateRecommendation(GlassParameters p) {

        p.setPatternType(2);

        double rRaw = electrical.computeRawResistance(p);
        double rTarget = electrical.computeTargetResistance(p);

        if (rRaw <= 0) {
            return "Некорректный R_raw. Проверьте размеры стекла, Rs и ориентацию шин.";
        }

        double multTarget = rTarget / rRaw;

        ProductionSearchService.MaxAchievable max = productionSearchService.estimateMaxAchievable(p);

        if (multTarget > max.maxMultiplier) {
            return "❌ Недостижимо в текущих диапазонах. Требуемый multiplier ≈ " + fmt2(multTarget)
                    + ", максимум ≈ " + fmt2(max.maxMultiplier)
                    + " (лучшее: a=" + fmt1(max.bestA) + " мм, gap=" + fmt1(max.bestGap) + " мм). "
                    + "Рекомендация: уменьшить Rs покрытия / изменить ограничения производства.";
        }

        Double suggestedA = findMinAForTarget(p, multTarget, gapMin);
        Double suggestedGap = findMinGapForTarget(p, multTarget, aMin);

        StringBuilder sb = new StringBuilder();
        sb.append("⚙ Требуемый multiplier ≈ ").append(fmt2(multTarget)).append(". ");

        if (suggestedA != null) {
            sb.append("Рекомендуется уменьшить a до ≈ ").append(fmt1(suggestedA))
                    .append(" мм при gap≈").append(fmt1(gapMin)).append(" мм. ");
        }

        if (suggestedGap != null) {
            sb.append("Альтернатива: уменьшить gap до ≈ ").append(fmt1(suggestedGap))
                    .append(" мм при a≈").append(fmt1(aMin)).append(" мм. ");
        }

        sb.append("Выберите вариант с меньшим |dev| и лучшей технологичностью.");
        return sb.toString();
    }

    private Double findMinAForTarget(GlassParameters p, double targetMultiplier, double fixedGap) {
        for (double a = aMin; a <= aMax + 1e-9; a += aStep) {
            double m = estimator.estimateMultiplier(p, a, fixedGap);
            if (m >= targetMultiplier) return a;
        }
        return null;
    }

    private Double findMinGapForTarget(GlassParameters p, double targetMultiplier, double fixedA) {
        for (double gap = gapMin; gap <= gapMax + 1e-9; gap += gapStep) {
            double m = estimator.estimateMultiplier(p, fixedA, gap);
            if (m >= targetMultiplier) return gap;
        }
        return null;
    }

    private String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }
    private String fmt2(double v) { return String.format(Locale.US, "%.2f", v); }
}
