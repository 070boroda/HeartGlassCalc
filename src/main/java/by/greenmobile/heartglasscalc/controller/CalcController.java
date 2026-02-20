package by.greenmobile.heartglasscalc.controller;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.EngineeringFacade;
import by.greenmobile.heartglasscalc.service.SvgGeneratorService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class CalcController {

    private final EngineeringFacade engineeringFacade;
    private final SvgGeneratorService svgGeneratorService;

    @GetMapping("/")
    public String index(Model model) {
        GlassParameters params = new GlassParameters();
        params.setPatternType(2);
        params.setBusbarOrientation(1);
        params.setEdgeOffset(12.0);
        params.setBusbarWidth(5.0);
        params.setHexSide(30.0);
        params.setHexGap(5.0);

        // params.setBusbarClearanceMm(3.0);

        model.addAttribute("params", params);
        return "index";
    }

    /**
     * Основной расчёт: считаем solver'ом, сохраняем в сессию и отдаём result.html.
     * URL оставлен /manual, чтобы не трогать index.html.
     */
    @PostMapping("/manual")
    public String manual(@ModelAttribute("params") GlassParameters params,
                         Model model,
                         HttpSession session) {
        params.setPatternType(2);

        GlassParameters result = engineeringFacade.solve(params);
        String svg = svgGeneratorService.generateSvg(result);

        session.setAttribute(PreviewController.SESSION_LAST_RESULT, result);

        model.addAttribute("result", result);
        model.addAttribute("svg", svg);
        return "result";
    }

    /**
     * Чтобы кнопка "← К результатам" и прямой заход /result работали без пересчёта.
     */
    @GetMapping("/result")
    public String result(Model model, HttpSession session) {
        GlassParameters last = (GlassParameters) session.getAttribute(PreviewController.SESSION_LAST_RESULT);
        if (last == null) {
            model.addAttribute("error", "Нет последнего результата. Сначала выполните расчёт.");
            return "result";
        }

        String svg = svgGeneratorService.generateSvg(last);
        model.addAttribute("result", last);
        model.addAttribute("svg", svg);
        return "result";
    }
}
