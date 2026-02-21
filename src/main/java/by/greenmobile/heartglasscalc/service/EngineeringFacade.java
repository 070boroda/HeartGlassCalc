package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import by.greenmobile.heartglasscalc.service.engine.HoneycombEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EngineeringFacade {
    private final ElectricalEngine electrical;
    private final HoneycombEstimator estimator;

    public GlassParameters calculateManual(GlassParameters p) {
        if (p == null) return null;

        // honeycomb
        p.setPatternType(2);

        // ВАЖНО: расчёт по активной зоне (между шинами)
        double areaM2 = electrical.computeActiveAreaM2(p);
        double rTarget = electrical.computeTargetResistance(p, true);
        p.setTotalResistance(rTarget);

        double rRaw = electrical.computeRawResistance(p);
        p.setRawResistance(rRaw);

        double a = p.getHexSide() != null ? p.getHexSide() : 0.0;
        double gap = p.getHexGap() != null ? p.getHexGap() : 0.0;

        double multFact = estimator.estimateMultiplier(p, a, gap);
        p.setPathLengthMultiplier(multFact);

        double rAch = rRaw * multFact;
        p.setAchievedResistance(rAch);

        double pWm2 = electrical.computePowerWm2(rAch, areaM2);
        p.setAchievedPowerWm2(pWm2);

        double dev = 0.0;
        if (p.getTargetPower() != null && p.getTargetPower() > 0) {
            dev = (pWm2 - p.getTargetPower()) / p.getTargetPower() * 100.0;
        }
        p.setPowerDeviationPercent(dev);

        log.info("MANUAL(active): areaM2={}, R_target={}, R_raw={}, mult={}, R_ach={}, P_ach={}, dev={}",
                areaM2, rTarget, rRaw, multFact, rAch, pWm2, dev);

        return p;
    }
}