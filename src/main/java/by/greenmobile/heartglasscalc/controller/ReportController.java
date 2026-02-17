package by.greenmobile.heartglasscalc.controller;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.EngineeringFacade;
import by.greenmobile.heartglasscalc.service.report.PdfReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Controller
@RequiredArgsConstructor
public class ReportController {

    private final EngineeringFacade engineeringFacade;
    private final PdfReportService pdfReportService;

    @PostMapping("/report/pdf")
    public ResponseEntity<byte[]> reportPdf(@ModelAttribute GlassParameters params) throws IOException {
        params.setPatternType(2);
        GlassParameters result = engineeringFacade.calculateManual(params);

        byte[] pdf = pdfReportService.buildEngineeringReport(result);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"engineering_report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
