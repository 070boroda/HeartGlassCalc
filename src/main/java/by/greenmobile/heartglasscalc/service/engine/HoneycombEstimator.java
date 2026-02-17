package by.greenmobile.heartglasscalc.service.engine;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
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

    public double estimateMultiplier(GlassParameters p, double a, double gap) {
        if (a <= 0 || gap < 0) return 0;

        if ("LEGACY".equalsIgnoreCase(model)) {
            return estimateLegacy(p, a, gap);
        }
        return estimatePhysical(a, gap);
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
        // rho_L = 2/(sqrt(3)*a)
        double edgeLengthDensity = 2.0 / (Math.sqrt(3.0) * a);

        // f_abl ≈ rho_L * gap
        double ablatedFraction = edgeLengthDensity * gap;

        // f = 1 - f_abl
        double f = 1.0 - ablatedFraction;
        if (f < minConductFraction) f = minConductFraction;

        // tau = 1 + c*(gap/a)
        double tau = 1.0 + tortuosityCoeff * (gap / a);

        // multiplier = tau / f^alpha
        return tau / Math.pow(f, alpha);
    }
}
