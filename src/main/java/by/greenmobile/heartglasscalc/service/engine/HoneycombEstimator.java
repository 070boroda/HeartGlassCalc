package by.greenmobile.heartglasscalc.service.engine;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
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
     * Optional global calibration scale for the final multiplier.
     * Default 1.0 = no effect.
     */
    @Value("${honeycomb.multiplier.scale:1.0}")
    private double multiplierScale;

    public double estimateMultiplier(GlassParameters p, double a, double gap) {
        if (a <= 0 || gap < 0) return 0;

        double mult;
        if ("LEGACY".equalsIgnoreCase(model)) {
            mult = estimateLegacy(p, a, gap);
        } else {
            mult = estimatePhysical(a, gap);
        }

        // Apply optional calibration (default 1.0)
        mult *= multiplierScale;

        if (log.isDebugEnabled()) {
            log.debug(
                    "HONEYCOMB: model={} pattern={} a={} gap={} alpha={} tortCoeff={} minF={} legacyCoeff={} scale={} => mult={}",
                    model, physicalPattern, a, gap, alpha, tortuosityCoeff, minConductFraction, legacyCoeff, multiplierScale, mult
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
            return estimatePhysicalIslands(a, gap);
        }
        // default: old behavior (LINES)
        return estimatePhysicalLines(a, gap);
    }

    /**
     * Old model: ablated are lines of width=gap along honeycomb edges.
     * (Works if current flows inside cells and laser creates insulating grooves.)
     */
    private double estimatePhysicalLines(double a, double gap) {
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

    /**
     * New model (your case): ablated are hexagon islands; current flows in gaps between them.
     * Here gap is the conducting channel width between neighboring islands.
     *
     * Conducting fraction f is approximated by area fraction of channels in a hex tiling.
     * Let s = a + gap/sqrt(3). Then:
     * f = 1 - (a/s)^2
     *
     * Multiplier increases when f decreases (channels get narrower).
     */
    private double estimatePhysicalIslands(double a, double gap) {
        // Effective "cell" side that corresponds to hex island (side a) plus half-gap around it
        double s = a + gap / Math.sqrt(3.0);
        if (s <= 0) return 0;

        // Conducting area fraction (channels)
        double f = 1.0 - Math.pow(a / s, 2.0);
        if (f < minConductFraction) f = minConductFraction;

        // Tortuosity: narrower channels -> more tortuous current paths.
        // Here tortuosity should GROW when gap shrinks => use (a/gap), not (gap/a).
        double tau;
        if (gap > 0) {
            tau = 1.0 + tortuosityCoeff * (a / gap);
        } else {
            // gap==0 => channels closed, clamp hard
            tau = 1.0 + tortuosityCoeff * 1e6;
        }

        return tau / Math.pow(f, alpha);
    }
}