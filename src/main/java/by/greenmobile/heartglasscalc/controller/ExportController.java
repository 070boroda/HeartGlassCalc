package by.greenmobile.heartglasscalc.controller;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.DxfGenerator;
import by.greenmobile.heartglasscalc.service.SvgGeneratorService;
import by.greenmobile.heartglasscalc.service.report.PdfReportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static by.greenmobile.heartglasscalc.controller.PreviewController.SESSION_LAST_RESULT;

@Controller
@RequiredArgsConstructor
public class ExportController {

    private final SvgGeneratorService svgGeneratorService;
    private final DxfGenerator dxfGenerator;
    private final PdfReportService pdfReportService;

    @GetMapping("/export/svg")
    public ResponseEntity<byte[]> exportSvg(HttpSession session) {
        GlassParameters p = getLast(session);
        String svg = svgGeneratorService.generateSvg(p);

        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .headers(fileHeaders("drawing.svg"))
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(bytes);
    }

    @GetMapping("/export/dxf")
    public ResponseEntity<byte[]> exportDxf(HttpSession session) {
        GlassParameters p = getLast(session);
        String dxf = dxfGenerator.generateDxf(p);

        byte[] bytes = dxf.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .headers(fileHeaders("drawing.dxf"))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(HttpSession session) throws IOException {
        GlassParameters p = getLast(session);

        // Твой сервис уже должен вернуть byte[]
        byte[] pdf = pdfReportService.buildEngineeringReport(p);

        return ResponseEntity.ok()
                .headers(fileHeaders("report.pdf"))
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private GlassParameters getLast(HttpSession session) {
        Object obj = session.getAttribute(SESSION_LAST_RESULT);
        if (obj instanceof GlassParameters gp) return gp;
        throw new IllegalStateException("Нет последнего результата в сессии. Сначала выполните расчёт.");
    }

    private HttpHeaders fileHeaders(String baseName) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String name = baseName.replace(".", "_" + ts + ".");

        HttpHeaders h = new HttpHeaders();
        h.setContentDisposition(ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build());
        h.setCacheControl("no-cache, no-store, must-revalidate");
        h.setPragma("no-cache");
        return h;
    }
}
