package by.greenmobile.heartglasscalc.service.report;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class PdfReportService {

    private static final float M = 60;
    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();

    private PDType0Font font;
    private PDType0Font fontBold;

    public byte[] buildEngineeringReport(GlassParameters p) throws IOException {

        try (PDDocument doc = new PDDocument()) {

            loadFonts(doc);

            writeTitlePage(doc, p);
            writeMethodologyPage(doc, p);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ================= FONT LOADING =================

    private void loadFonts(PDDocument doc) throws IOException {

        try (InputStream is = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf")) {
            font = PDType0Font.load(doc, is, true);
        }
        try (InputStream isb = getClass().getResourceAsStream("/fonts/DejaVuSans-Bold.ttf")) {
            fontBold = PDType0Font.load(doc, isb, true);
        }
    }

    // ================= TITLE PAGE =================

    private void writeTitlePage(PDDocument doc, GlassParameters p) throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

            text(cs, fontBold, 24, M, H - 80,
                    "ИНЖЕНЕРНЫЙ ОТЧЁТ");
            text(cs, font, 14, M, H - 110,
                    "Обогреваемое стекло с абляционной структурой");

            text(cs, font, 10, W - 200, H - 60,
                    "Дата: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));

            float y = H - 150;

            y = sectionTitle(cs, y, "Исходные данные");

            y = kv(cs, y, "Размер стекла",
                    p.getWidth() + " x " + p.getHeight() + " мм");
            y = kv(cs, y, "Плоскостное сопротивление",
                    p.getSheetResistance() + " Ом/кв");
            y = kv(cs, y, "Целевая мощность",
                    p.getTargetPower() + " Вт/м2");

            y -= 15;
            y = sectionTitle(cs, y, "Расчётные результаты");

            y = kv(cs, y, "Требуемое сопротивление",
                    format(p.getTotalResistance()) + " Ом");
            y = kv(cs, y, "Фактическое сопротивление",
                    format(p.getAchievedResistance()) + " Ом");
            y = kv(cs, y, "Фактическая мощность",
                    format(p.getAchievedPowerWatts()) + " Вт");
            y = kv(cs, y, "Фактическая удельная мощность",
                    format(p.getAchievedPowerWm2()) + " Вт/м2");
            y = kv(cs, y, "Отклонение",
                    format(p.getPowerDeviationPercent()) + " %");

            y -= 15;
            y = sectionTitle(cs, y, "Геометрия рисунка");

            y = kv(cs, y, "Сторона соты",
                    p.getHexSide() + " мм");
            y = kv(cs, y, "Зазор",
                    p.getHexGap() + " мм");
            y = kv(cs, y, "Колонки x ряды",
                    p.getHexCols() + " x " + p.getHexRows());
        }
    }

    // ================= METHODOLOGY PAGE =================

    private void writeMethodologyPage(PDDocument doc, GlassParameters p) throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

            float y = H - 80;

            text(cs, fontBold, 18, M, y,
                    "Методика расчёта");
            y -= 30;

            y = paragraph(cs, y,
                    "1) Требуемое сопротивление рассчитывается по формуле:");
            y = paragraph(cs, y,
                    "R_target = U^2 / (P_target * S)");
            y -= 10;

            y = paragraph(cs, y,
                    "2) Базовое сопротивление покрытия определяется через плоскостное сопротивление:");
            y = paragraph(cs, y,
                    "R_raw = Rs * (L / W)");
            y -= 10;

            y = paragraph(cs, y,
                    "3) Коэффициент удлинения пути тока определяется как:");
            y = paragraph(cs, y,
                    "Multiplier = R_target / R_raw");
            y -= 10;

            y = paragraph(cs, y,
                    "4) Геометрия сот подбирается так, чтобы обеспечить необходимую длину пути тока.");
            y = paragraph(cs, y,
                    "Фактическая мощность пересчитывается по формуле:");
            y = paragraph(cs, y,
                    "P = U^2 / R_achieved");
        }
    }

    // ================= TEXT HELPERS =================

    private void text(PDPageContentStream cs,
                      PDType0Font f,
                      int size,
                      float x,
                      float y,
                      String text) throws IOException {

        cs.beginText();
        cs.setFont(f, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private float sectionTitle(PDPageContentStream cs, float y, String title) throws IOException {
        text(cs, fontBold, 14, M, y, title);
        return y - 20;
    }

    private float kv(PDPageContentStream cs, float y,
                     String key,
                     String value) throws IOException {

        text(cs, font, 11, M, y, key + ":");
        text(cs, fontBold, 11, M + 220, y, value);
        return y - 18;
    }

    private float paragraph(PDPageContentStream cs,
                            float y,
                            String text) throws IOException {

        float leading = 16;
        float maxWidth = W - 2 * M;

        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            float width = font.getStringWidth(test) / 1000 * 11;

            if (width > maxWidth) {
                writeLine(cs, y, line.toString());
                y -= leading;
                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) line.append(" ");
                line.append(word);
            }
        }

        if (!line.isEmpty()) {
            writeLine(cs, y, line.toString());
            y -= leading;
        }

        return y - 4;
    }

    private void writeLine(PDPageContentStream cs,
                           float y,
                           String text) throws IOException {
        cs.beginText();
        cs.setFont(font, 11);
        cs.newLineAtOffset(M, y);
        cs.showText(text);
        cs.endText();
    }

    private String format(Double v) {
        if (v == null) return "-";
        return String.format("%.2f", v);
    }
}
