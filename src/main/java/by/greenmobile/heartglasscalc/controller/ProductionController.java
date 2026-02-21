package by.greenmobile.heartglasscalc.controller;

import by.greenmobile.heartglasscalc.entity.CandidateDesign;
import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.entity.ProductionResult;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import by.greenmobile.heartglasscalc.service.production.ProductionSearchService;
import by.greenmobile.heartglasscalc.service.production.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class ProductionController {

    private final ProductionSearchService productionSearchService;
    private final RecommendationService recommendationService;
    private final ElectricalEngine electricalEngine;

    @PostMapping("/auto")
    public String auto(@ModelAttribute GlassParameters params, Model model) {
        params.setPatternType(2);

        List<CandidateDesign> top = productionSearchService.findTopDesigns(params);

        double rawR = electricalEngine.computeRawResistance(params);

        // ВАЖНО: целевое сопротивление считаем под targetPower (Вт/м²) по активной зоне
        double targetR = electricalEngine.computeTargetResistance(params, true);

        double requiredMult = (rawR > 0) ? (targetR / rawR) : 0;

        ProductionSearchService.MaxAchievable max = productionSearchService.estimateMaxAchievable(params);

        boolean achievable = !top.isEmpty() && Math.abs(top.get(0).getDeviationPercent()) <= 10.0;

        String rec = achievable
                ? "Цель достижима. Выберите вариант и нажмите «Применить»."
                : recommendationService.generateRecommendation(params);

        ProductionResult pr = new ProductionResult(
                top,
                achievable,
                rec,
                requiredMult,
                max.maxMultiplier,
                max.maxPowerWm2
        );

        model.addAttribute("baseParams", params);
        model.addAttribute("prod", pr);
        return "production";
    }
}