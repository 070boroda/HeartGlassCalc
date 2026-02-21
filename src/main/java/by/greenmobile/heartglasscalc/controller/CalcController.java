package by.greenmobile.heartglasscalc.controller;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.DxfGenerator;
import by.greenmobile.heartglasscalc.service.EngineeringFacade;
import by.greenmobile.heartglasscalc.service.SvgGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CalcController {

    private final EngineeringFacade engineeringFacade;
    private final SvgGeneratorService svgGeneratorService;
    private final DxfGenerator dxfGenerator;

    @GetMapping("/")
    public String index(Model model) {
        GlassParameters params = new GlassParameters();
        params.setPatternType(2);
        params.setBusbarOrientation(1);
        params.setEdgeOffset(12.0);
        params.setBusbarWidth(5.0);
        params.setHexSide(30.0);
        params.setHexGap(5.0);
        params.setBusbarClearanceMm(2.00);

        model.addAttribute("params", params);
        return "index";
    }

    @PostMapping("/manual")
    public String manual(@ModelAttribute GlassParameters params, Model model) {
        params.setPatternType(2);

        log.info("HTTP /manual: W={} H={} Rs={} targetWm2={} EO={} BBW={} CLR={} orient={} a={} gap={}",
                params.getWidth(), params.getHeight(), params.getSheetResistance(), params.getTargetPower(),
                params.getEdgeOffset(), params.getBusbarWidth(), params.getBusbarClearanceMm(), params.getBusbarOrientation(),
                params.getHexSide(), params.getHexGap()
        );

        GlassParameters result = engineeringFacade.calculateManual(params);
        String svg = svgGeneratorService.generateSvg(result);

        model.addAttribute("result", result);
        model.addAttribute("svg", svg);
        return "result";
    }

    @PostMapping("/download/svg")
    public ResponseEntity<byte[]> downloadSvg(@ModelAttribute GlassParameters params) {
        params.setPatternType(2);

        log.info("HTTP /download/svg: W={} H={} Rs={} EO={} BBW={} CLR={} orient={} a={} gap={}",
                params.getWidth(), params.getHeight(), params.getSheetResistance(),
                params.getEdgeOffset(), params.getBusbarWidth(), params.getBusbarClearanceMm(), params.getBusbarOrientation(),
                params.getHexSide(), params.getHexGap()
        );

        GlassParameters calc = engineeringFacade.calculateManual(params);
        String svg = svgGeneratorService.generateSvg(calc);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heater_pattern.svg\"")
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(svg.getBytes());
    }

    @PostMapping("/download/dxf")
    public ResponseEntity<byte[]> downloadDxf(@ModelAttribute GlassParameters params) {
        params.setPatternType(2);

        log.info("HTTP /download/dxf: W={} H={} Rs={} EO={} BBW={} CLR={} orient={} a={} gap={}",
                params.getWidth(), params.getHeight(), params.getSheetResistance(),
                params.getEdgeOffset(), params.getBusbarWidth(), params.getBusbarClearanceMm(), params.getBusbarOrientation(),
                params.getHexSide(), params.getHexGap()
        );

        GlassParameters calc = engineeringFacade.calculateManual(params);
        String dxf = dxfGenerator.generateDxf(calc);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heater_pattern.dxf\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(dxf.getBytes());
    }
}