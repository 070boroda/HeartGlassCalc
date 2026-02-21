package by.greenmobile.heartglasscalc.service.engine;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import org.springframework.stereotype.Component;

@Component
public class ElectricalEngine {
    private static final double VOLTAGE = 220.0;

    private double nz(Double v, double def) {
        return v == null ? def : v;
    }

    /**
     * Полная площадь стекла, м² (как было раньше).
     */
    public double computeAreaM2(GlassParameters p) {
        return (nz(p.getWidth(), 0) * nz(p.getHeight(), 0)) / 1_000_000.0;
    }

    /**
     * Активная ширина/высота без краевого отступа.
     */
    public double activeWidthMm(GlassParameters p) {
        return nz(p.getWidth(), 0) - 2.0 * nz(p.getEdgeOffset(), 0);
    }

    public double activeHeightMm(GlassParameters p) {
        return nz(p.getHeight(), 0) - 2.0 * nz(p.getEdgeOffset(), 0);
    }

    /**
     * Эффективные L/W (мм) для Rs*(L/W) между шинами, с учётом:
     * - edgeOffset
     * - busbarWidth + busbarClearanceMm (с обеих сторон)
     *
     * Возвращает массив {L_mm, W_mm}.
     *
     * Примечание: в текущем проекте isVerticalBusbars() по факту означает
     * "шины сверху/снизу" (ток течёт по высоте).
     */
    public double[] effectiveLWmm(GlassParameters p) {
        boolean topBottom = p.isVerticalBusbars();

        double aw = activeWidthMm(p);
        double ah = activeHeightMm(p);

        double bb = nz(p.getBusbarWidth(), 0);
        double clr = nz(p.getBusbarClearanceMm(), 0);

        if (topBottom) {
            // Шины сверху/снизу: L по высоте активной зоны минус зона шин+зазоров сверху/снизу
            double L = ah - 2.0 * (bb + clr);
            double W = aw;
            return new double[]{L, W};
        } else {
            // Шины слева/справа: L по ширине активной зоны минус зона шин+зазоров слева/справа
            double L = aw - 2.0 * (bb + clr);
            double W = ah;
            return new double[]{L, W};
        }
    }

    /**
     * Активная площадь нагрева (между шинами), м².
     */
    public double computeActiveAreaM2(GlassParameters p) {
        double[] lw = effectiveLWmm(p);
        double L = lw[0], W = lw[1];
        if (L <= 0 || W <= 0) return 0;
        return (L * W) / 1_000_000.0;
    }

    /**
     * Сырой расчёт сопротивления сплошного покрытия между шинами:
     * R_raw = Rs * (L_eff / W_eff)
     */
    public double computeRawResistance(GlassParameters p) {
        double[] lw = effectiveLWmm(p);
        double L = lw[0], W = lw[1];
        if (L <= 0 || W <= 0) return 0;
        return nz(p.getSheetResistance(), 0) * (L / W);
    }

    /**
     * Целевое сопротивление под targetPower (Вт/м²).
     * Если useActiveArea=true, targetPower трактуется как Вт/м² по активной зоне между шинами.
     */
    public double computeTargetResistance(GlassParameters p, boolean useActiveArea) {
        double area = useActiveArea ? computeActiveAreaM2(p) : computeAreaM2(p);
        double target = nz(p.getTargetPower(), 0);
        if (area <= 0 || target <= 0) return 0;
        return (VOLTAGE * VOLTAGE) / (target * area);
    }

    /**
     * Старый метод оставлен для совместимости: targetPower по полной площади.
     */
    public double computeTargetResistance(GlassParameters p) {
        return computeTargetResistance(p, false);
    }

    public double computePowerWm2(double resistance, double areaM2) {
        if (resistance <= 0 || areaM2 <= 0) return 0;
        double pTotal = (VOLTAGE * VOLTAGE) / resistance;
        return pTotal / areaM2;
    }
}