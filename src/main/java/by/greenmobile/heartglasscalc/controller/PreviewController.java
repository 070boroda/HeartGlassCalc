package by.greenmobile.heartglasscalc.controller;


import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.SvgGeneratorService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class PreviewController {

    public static final String SESSION_LAST_RESULT = "LAST_RESULT";

    private final SvgGeneratorService svgGeneratorService;

    @GetMapping("/preview")
    public String preview(Model model, HttpSession session) {
        GlassParameters p = (GlassParameters) session.getAttribute(SESSION_LAST_RESULT);
        if (p == null) {
            model.addAttribute("error", "Нет данных для предпросмотра. Сначала выполните расчёт.");
            return "preview";
        }

        String svg = svgGeneratorService.generateSvg(p);
        model.addAttribute("result", p);
        model.addAttribute("svg", svg);

        return "preview";
    }
}
