package by.greenmobile.heartglasscalc.service.engine;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.calibration.CalibrationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HoneycombEstimator {

    @Value("${honeycomb.model:PHYSICAL}")
    private String model;

    // LEGACY
    @Value("${honeycomb.legacyCoeff:0.35}")
    private double legacyCoeff;

    // PHYSICAL
    @Value("${honeycomb.physical.alpha:1.0}")
    private double alpha;

    @Value("${honeycomb.physical.tortuosityCoeff:1.5}")
    private double tortuosityCoeff;

    @Value("${honeycomb.physical.minConductFraction:0.10}")
    private double minConductFraction;

    // PHYSICAL pattern:
    // LINES   - ablated are lines/kerf with width=gap (old behavior)
    // ISLANDS - ablated are hexagon islands; current flows in conducting gaps between them (your case)
    @Value("${honeycomb.physical.pattern:ISLANDS}")
    private String physicalPattern;

    /**
     * Калибровочный scale читается из {@link CalibrationStore} (рантайм-значение),
     * а не из @Value напрямую. Это позволяет менять scale из UI без перезапуска.
     */
    private final CalibrationStore calibrationStore;

    public HoneycombEstimator(CalibrationStore calibrationStore) {
        this.calibrationStore = calibrationStore;
    }

    public double estimateMultiplier(GlassParameters p, double a, double gap) {
        if (a <= 0 || gap < 0) return 0;

        double mult;
        if ("LEGACY".equalsIgnoreCase(model)) {
            mult = estimateLegacy(p, a, gap);
        } else {
            mult = estimatePhysical(a, gap);
        }

        double scale = calibrationStore.getCurrent();
        mult *= scale;

        if (log.isDebugEnabled()) {
            log.debug(
                    "HONEYCOMB: model={} pattern={} a={} gap={} alpha={} tortCoeff={} minF={} legacyCoeff={} scale={} => mult={}",
                    model, physicalPattern, a, gap, alpha, tortuosityCoeff, minConductFraction, legacyCoeff, scale, mult
            );
        }

        return mult;
    }

    private double estimateLegacy(GlassParameters p, double a, double gap) {
        double workingWidth = p.getWidth() - 2 * p.getEdgeOffset();
        double workingHeight = p.getHeight() - 2 * p.getEdgeOffset();
        if (workingWidth <= 0 || workingHeight <= 0) return 0;

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;
        if (stepX <= 0 || stepY <= 0) return 0;

        double densityX = workingWidth / stepX;
        double densityY = workingHeight / stepY;
        double cellCount = densityX * densityY;

        double totalPerimeter = 6.0 * a * cellCount;

        double directionLength = p.isVerticalBusbars() ? workingHeight : workingWidth;
        if (directionLength <= 0) return 0;

        return legacyCoeff * totalPerimeter / directionLength;
    }

    private double estimatePhysical(double a, double gap) {
        if ("ISLANDS".equalsIgnoreCase(physicalPattern)) {
            return computeIslandsMultiplier(a, gap, alpha, tortuosityCoeff, minConductFraction);
        }
        return computeLinesMultiplier(a, gap, alpha, tortuosityCoeff, minConductFraction);
    }

    // ========================================================================
    // Чистые формулы (package-private, без Spring — тестируются напрямую)
    // ========================================================================

    /**
     * Геометрически точная доля ПРОВОДЯЩЕЙ площади для регулярной flat-top
     * гексагональной решётки из изолирующих островков.
     *
     * <p>Площадь одного шестиугольника: S_hex = (3√3/2)·a².
     * Площадь, приходящаяся на одну ячейку (hex + его доля канала):
     * S_cell = (1.5·a + gap)·(√3·a + gap).
     * Тогда f = 1 − S_hex / S_cell.
     *
     * <p>Граничные случаи: при a≤0 возвращает 0; при gap=0 даёт f=0 (каналы закрыты);
     * при gap→∞ даёт f→1.
     *
     * @return f в диапазоне [0..1] БЕЗ клиппинга по minConductFraction
     */
    static double islandsConductingFraction(double a, double gap) {
        if (a <= 0) return 0;
        double sHex = (3.0 * Math.sqrt(3.0) / 2.0) * a * a;
        double stepX = 1.5 * a + gap;
        double stepY = Math.sqrt(3.0) * a + gap;
        double sCell = stepX * stepY;
        if (sCell <= 0) return 0;
        double f = 1.0 - sHex / sCell;
        if (f < 0) return 0;
        if (f > 1) return 1;
        return f;
    }

    /**
     * Эмпирическая тортуозность канала: τ → ∞ при gap → 0.
     * При gap=0 возвращает большое конечное значение (защита от деления на ноль).
     */
    static double islandsTortuosity(double a, double gap, double tortuosityCoeff) {
        if (gap > 0) {
            return 1.0 + tortuosityCoeff * (a / gap);
        }
        return 1.0 + tortuosityCoeff * 1e6;
    }

    /**
     * Множитель сопротивления для паттерна ISLANDS:
     * <pre>mult = τ(a, gap) / f(a, gap)^α</pre>
     * с клиппингом f снизу до minConductFraction.
     */
    static double computeIslandsMultiplier(double a, double gap,
                                           double alpha,
                                           double tortuosityCoeff,
                                           double minConductFraction) {
        if (a <= 0 || gap < 0) return 0;
        double f = islandsConductingFraction(a, gap);
        if (f < minConductFraction) f = minConductFraction;
        double tau = islandsTortuosity(a, gap, tortuosityCoeff);
        return tau / Math.pow(f, alpha);
    }

    /**
     * Старая модель LINES: абляция — изолирующие канавки шириной gap по рёбрам сот.
     * Здесь gap трактуется как ширина пропила.
     */
    static double computeLinesMultiplier(double a, double gap,
                                         double alpha,
                                         double tortuosityCoeff,
                                         double minConductFraction) {
        if (a <= 0 || gap < 0) return 0;
        double edgeLengthDensity = 2.0 / (Math.sqrt(3.0) * a);
        double ablatedFraction = edgeLengthDensity * gap;
        double f = 1.0 - ablatedFraction;
        if (f < minConductFraction) f = minConductFraction;
        double tau = 1.0 + tortuosityCoeff * (gap / a);
        return tau / Math.pow(f, alpha);
    }
}