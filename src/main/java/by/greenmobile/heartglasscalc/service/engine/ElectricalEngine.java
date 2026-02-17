package by.greenmobile.heartglasscalc.service.engine;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import org.springframework.stereotype.Component;

@Component
public class ElectricalEngine {

    private static final double VOLTAGE = 220.0;

    public double computeAreaM2(GlassParameters p) {
        return (p.getWidth() * p.getHeight()) / 1_000_000.0;
    }

    public double computeRawResistance(GlassParameters p) {
        boolean vertical = p.isVerticalBusbars();
        double L = vertical ? p.getHeight() : p.getWidth();
        double W = vertical ? p.getWidth() : p.getHeight();
        return p.getSheetResistance() * (L / W);
    }

    public double computeTargetResistance(GlassParameters p) {
        double area = computeAreaM2(p);
        return (VOLTAGE * VOLTAGE) / (p.getTargetPower() * area);
    }

    public double computePowerWm2(double resistance, double areaM2) {
        if (resistance <= 0 || areaM2 <= 0) return 0;
        double pTotal = (VOLTAGE * VOLTAGE) / resistance;
        return pTotal / areaM2;
    }
}
