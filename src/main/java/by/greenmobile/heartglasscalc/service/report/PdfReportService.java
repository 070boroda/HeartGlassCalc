package by.greenmobile.heartglasscalc.service.report;

import by.greenmobile.heartglasscalc.entity.GlassParameters;
import by.greenmobile.heartglasscalc.service.engine.ElectricalEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class PdfReportService {

    private static final float M = 50f;
    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float MAX_WIDTH = W - 2 * M;

    private static final double U = 220.0;

    private PDType0Font font;
    private PDType0Font fontBold;

    private final ElectricalEngine electrical;

    public PdfReportService(ElectricalEngine electrical) {
        this.electrical = electrical;
    }

    // ---- Honeycomb model parameters (printed in report) ----
    @Value("${honeycomb.model:PHYSICAL}")
    private String honeycombModel;

    @Value("${honeycomb.physical.pattern:ISLANDS}")
    private String honeycombPattern;

    @Value("${honeycomb.physical.alpha:1.0}")
    private double honeycombAlpha;

    @Value("${honeycomb.physical.tortuosityCoeff:1.5}")
    private double honeycombTortuosity;

    @Value("${honeycomb.physical.minConductFraction:0.10}")
    private double honeycombMinConductFraction;

    @Value("${honeycomb.legacyCoeff:0.35}")
    private double honeycombLegacyCoeff;

    @Value("${honeycomb.multiplier.scale:1.0}")
    private double honeycombScale;

    public byte[] buildEngineeringReport(GlassParameters p) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            loadFonts(doc);

            Cursor c = new Cursor(doc);
            writeHeader(c, "ИНЖЕНЕРНЫЙ ОТЧЁТ");
            writeSummaryPage(c, p);

            c.newPage();
            writeHeader(c, "ВХОДНЫЕ ДАННЫЕ");
            writeInputsPage(c, p);

            c.newPage();
            writeHeader(c, "РАСЧЁТ ПО ШАГАМ");
            writeCalculationPage(c, p);

            c.newPage();
            writeHeader(c, "ПРИМЕЧАНИЯ");
            writeNotesPage(c);

            // ✅ IMPORTANT: close stream before saving
            c.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void writeSummaryPage(Cursor c, GlassParameters p) throws IOException {
        float y = c.y;

        y = h2(c, y, "Краткая сводка");
        y = kv(c, y, "Дата", LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        y = kv(c, y, "Напряжение питания", fmt(U) + " В");

        double areaActive = electrical.computeActiveAreaM2(p);
        double rTarget = electrical.computeTargetResistance(p, true);
        double rRaw = electrical.computeRawResistance(p);

        double rAch = nz(p.getAchievedResistance());
        double pTotal = (rAch > 0) ? (U * U / rAch) : 0.0;
        double pWm2 = (areaActive > 0 && rAch > 0) ? (pTotal / areaActive) : 0.0;

        y -= 6;
        y = h2(c, y, "Итог");
        y = kv(c, y, "Целевая удельная мощность", fmt(p.getTargetPower()) + " Вт/м²");
        y = kv(c, y, "Фактическая удельная мощность", fmt(pWm2) + " Вт/м²");
        y = kv(c, y, "Отклонение", fmt(p.getPowerDeviationPercent()) + " %");

        y -= 6;
        y = kv(c, y, "R_target", fmt(rTarget) + " Ом");
        y = kv(c, y, "R_raw", fmt(rRaw) + " Ом");
        y = kv(c, y, "R_ach", fmt(p.getAchievedResistance()) + " Ом");
        y = kv(c, y, "P_total", fmt(pTotal) + " Вт");
        y = kv(c, y, "S_active", fmt(areaActive) + " м²");

        y -= 10;
        y = h2(c, y, "Геометрия рисунка (соты)");
        y = kv(c, y, "a", fmt(p.getHexSide()) + " мм");
        y = kv(c, y, "gap", fmt(p.getHexGap()) + " мм");
        y = kv(c, y, "Колонки × ряды", safe(p.getHexCols()) + " × " + safe(p.getHexRows()));

        // ===== Calibration block (NEW) =====
        if (p.getMeasuredResistance() != null && p.getMeasuredResistance() > 0) {
            y -= 10;
            y = h2(c, y, "Калибровка по измерению (если задано R_fact)");
            y = kv(c, y, "R_fact (измеренное)", fmt(p.getMeasuredResistance()) + " Ом");
            y = kv(c, y, "R_calc (расчётное)", fmt(p.getAchievedResistance()) + " Ом");
            y = kv(c, y, "Рекомендуемый multiplier.scale", fmt(p.getRecommendedMultiplierScale()));
            y = kv(c, y, "Ошибка (R_calc vs R_fact)", fmt(p.getCalibrationErrorPercent()) + " %");
        }

        c.y = y;
    }

    private void writeInputsPage(Cursor c, GlassParameters p) throws IOException {
        float y = c.y;

        y = h2(c, y, "Геометрия стекла и шин");
        y = kv(c, y, "Ширина × высота", fmt(p.getWidth()) + " × " + fmt(p.getHeight()) + " мм");
        y = kv(c, y, "Edge offset", fmt(p.getEdgeOffset()) + " мм");
        y = kv(c, y, "Ширина шины", fmt(p.getBusbarWidth()) + " мм");
        y = kv(c, y, "Clearance от шины", fmt(p.getBusbarClearanceMm()) + " мм");
        y = kv(c, y, "Ориентация шин", (p.isVerticalBusbars() ? "верх / низ (ток по высоте)" : "лево / право (ток по ширине)"));

        y -= 8;
        y = h2(c, y, "Электрические входные параметры");
        y = kv(c, y, "Плоскостное сопротивление Rs", fmt(p.getSheetResistance()) + " Ом/□");
        y = kv(c, y, "Целевая удельная мощность", fmt(p.getTargetPower()) + " Вт/м²");
        y = kv(c, y, "Напряжение питания", fmt(U) + " В");

        y -= 8;
        y = h2(c, y, "Соты (Honeycomb)");
        y = kv(c, y, "Сторона островка a", fmt(p.getHexSide()) + " мм");
        y = kv(c, y, "gap", fmt(p.getHexGap()) + " мм");
        y = paragraph(c, y,
                "Пояснение: при pattern=ISLANDS параметр gap трактуется как ширина ПРОВОДЯЩЕЙ дорожки/канала между изолированными островками. "
                        + "При уменьшении gap сопротивление возрастает.");

        if (p.getMeasuredResistance() != null && p.getMeasuredResistance() > 0) {
            y -= 6;
            y = h2(c, y, "Калибровка (вход)");
            y = kv(c, y, "R_fact (измеренное сопротивление)", fmt(p.getMeasuredResistance()) + " Ом");
        }

        y -= 6;
        y = h2(c, y, "Параметры модели множителя");
        y = kv(c, y, "honeycomb.model", honeycombModel);
        y = kv(c, y, "honeycomb.physical.pattern", honeycombPattern);
        y = kv(c, y, "alpha", fmt(honeycombAlpha));
        y = kv(c, y, "tortuosityCoeff", fmt(honeycombTortuosity));
        y = kv(c, y, "minConductFraction", fmt(honeycombMinConductFraction));
        y = kv(c, y, "legacyCoeff", fmt(honeycombLegacyCoeff));
        y = kv(c, y, "multiplier.scale (текущее)", fmt(honeycombScale));

        c.y = y;
    }

    private void writeCalculationPage(Cursor c, GlassParameters p) throws IOException {
        float y = c.y;

        double[] lw = electrical.effectiveLWmm(p);
        double L_eff = lw[0];
        double W_eff = lw[1];
        double area = electrical.computeActiveAreaM2(p);

        double rs = nz(p.getSheetResistance());
        double targetWm2 = nz(p.getTargetPower());

        double rTarget = electrical.computeTargetResistance(p, true);
        double rRaw = electrical.computeRawResistance(p);

        double mRequired = (rRaw > 0) ? (rTarget / rRaw) : 0.0;
        double mEstimated = nz(p.getPathLengthMultiplier());
        double rAch = nz(p.getAchievedResistance());

        double pTotal = (rAch > 0) ? (U * U / rAch) : 0.0;
        double pWm2 = (area > 0 && rAch > 0) ? (pTotal / area) : 0.0;

        y = h2(c, y, "Шаг 1. Активная геометрия");
        y = kv(c, y, "L_eff", fmt(L_eff) + " мм");
        y = kv(c, y, "W_eff", fmt(W_eff) + " мм");
        y = kv(c, y, "S_active", fmt(area) + " м²");

        y -= 8;
        y = h2(c, y, "Шаг 2. Требуемое сопротивление");
        y = codeLine(c, y, "R_target = U² / (P_target · S_active)");
        y = codeLine(c, y,
                "R_target = " + fmt(U) + "² / (" + fmt(targetWm2) + " · " + fmt(area) + ") = " + fmt(rTarget) + " Ом");

        y -= 6;
        y = h2(c, y, "Шаг 3. Базовое сопротивление покрытия");
        y = codeLine(c, y, "R_raw = Rs · (L_eff / W_eff)");
        y = codeLine(c, y,
                "R_raw = " + fmt(rs) + " · (" + fmt(L_eff) + " / " + fmt(W_eff) + ") = " + fmt(rRaw) + " Ом");

        y -= 6;
        y = h2(c, y, "Шаг 4. Множители");
        y = kv(c, y, "M_required = R_target / R_raw", fmt(mRequired));
        y = kv(c, y, "M_estimated (модель)", fmt(mEstimated));

        y -= 6;
        y = h2(c, y, "Шаг 5. Итоговое сопротивление и мощность");
        y = kv(c, y, "R_ach = R_raw · M_estimated", fmt(rAch) + " Ом");
        y = kv(c, y, "P_total = U² / R_ach", fmt(pTotal) + " Вт");
        y = kv(c, y, "P_wm2 = P_total / S_active", fmt(pWm2) + " Вт/м²");
        y = kv(c, y, "Отклонение", fmt(p.getPowerDeviationPercent()) + " %");

        // Calibration output
        if (p.getMeasuredResistance() != null && p.getMeasuredResistance() > 0) {
            y -= 8;
            y = h2(c, y, "Калибровка (расчёт)");
            y = kv(c, y, "R_fact", fmt(p.getMeasuredResistance()) + " Ом");
            y = kv(c, y, "R_calc", fmt(p.getAchievedResistance()) + " Ом");
            y = kv(c, y, "Рекомендуемый multiplier.scale", fmt(p.getRecommendedMultiplierScale()));
            y = kv(c, y, "Ошибка, %", fmt(p.getCalibrationErrorPercent()));
            y = paragraph(c, y,
                    "Рекомендация: запишите значение recommendedMultiplierScale в application.properties как honeycomb.multiplier.scale и перезапустите приложение.");
        }

        c.y = y;
    }

    private void writeNotesPage(Cursor c) throws IOException {
        float y = c.y;

        y = h2(c, y, "Ограничения и рекомендации");
        y = paragraph(c, y,
                "1) Расчёт выполнен для активной зоны нагрева между шинами (S_active). "
                        + "Краевые зоны (edgeOffset) и зоны шин/зазоров (busbarWidth + clearance) в активную площадь не входят.");
        y = paragraph(c, y,
                "2) Плоскостное сопротивление Rs (Ом/□) должно измеряться корректно. "
                        + "Измерение мультиметром двумя точками на изделии может отличаться от Rs из-за контакта, "
                        + "неравномерности покрытия и распределения тока.");
        y = paragraph(c, y,
                "3) Множитель M_estimated — модельная оценка. Для точного совпадения с производством используйте калибровку "
                        + "параметром honeycomb.multiplier.scale по реальной точке (a, gap, R_fact).");
        y = paragraph(c, y,
                "4) Для pattern=ISLANDS параметр gap — это ширина проводящего канала между изолированными островками. "
                        + "Уменьшение gap, как правило, увеличивает сопротивление и уменьшает удельную мощность.");

        c.y = y;
    }

    // ===== Fonts =====

    private void loadFonts(PDDocument doc) throws IOException {
        font = loadFontOrThrow(doc, "/fonts/DejaVuSans.ttf", "DejaVuSans.ttf");
        fontBold = loadFontOrThrow(doc, "/fonts/DejaVuSans-Bold.ttf", "DejaVuSans-Bold.ttf");
    }

    private PDType0Font loadFontOrThrow(PDDocument doc, String resourcePath, String humanName) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Не найден шрифт в resources: " + resourcePath + " (" + humanName + "). "
                        + "Проверь наличие файлов в src/main/resources/fonts/");
            }
            return PDType0Font.load(doc, is, true);
        }
    }

    // ===== Drawing helpers =====

    private void writeHeader(Cursor c, String title) throws IOException {
        float y = H - 70;
        text(c, fontBold, 18, M, y, title);
        text(c, font, 10, W - 210, y + 4, "Дата: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        c.y = H - 105;
    }

    private float h2(Cursor c, float y, String t) throws IOException {
        y = c.ensureSpace(y, 28);
        text(c, fontBold, 13, M, y, t);
        return y - 18;
    }

    private float kv(Cursor c, float y, String key, String value) throws IOException {
        y = c.ensureSpace(y, 18);
        text(c, font, 11, M, y, key + ":");
        text(c, fontBold, 11, M + 260, y, value);
        return y - 16;
    }

    private float paragraph(Cursor c, float y, String text) throws IOException {
        return wrappedText(c, y, text, font, 11, 16, 0);
    }

    private float codeLine(Cursor c, float y, String text) throws IOException {
        return wrappedText(c, y, text, font, 11, 16, 14);
    }

    private float wrappedText(Cursor c, float y, String text, PDType0Font f, int size, float leading, float indent) throws IOException {
        y = c.ensureSpace(y, leading + 6);

        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();

        float x0 = M + indent;
        float maxW = MAX_WIDTH - indent;

        for (String word : words) {
            String test = (line.length() == 0) ? word : (line + " " + word);
            float tw = f.getStringWidth(test) / 1000f * size;

            if (tw > maxW && line.length() > 0) {
                y = c.ensureSpace(y, leading);
                text(c, f, size, x0, y, line.toString());
                y -= leading;
                line = new StringBuilder(word);
            } else {
                if (line.length() > 0) line.append(" ");
                line.append(word);
            }
        }

        if (line.length() > 0) {
            y = c.ensureSpace(y, leading);
            text(c, f, size, x0, y, line.toString());
            y -= leading;
        }

        return y - 4;
    }

    private void text(Cursor c, PDType0Font f, int size, float x, float y, String t) throws IOException {
        c.cs.beginText();
        c.cs.setFont(f, size);
        c.cs.newLineAtOffset(x, y);
        c.cs.showText(t);
        c.cs.endText();
    }

    // ===== Formatting =====

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private static String safe(Integer v) {
        return v == null ? "-" : String.valueOf(v);
    }

    private static String fmt(Double v) {
        if (v == null) return "-";
        return fmt(v.doubleValue());
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    // ===== Cursor / pagination =====

    private class Cursor {
        final PDDocument doc;
        PDPage page;
        PDPageContentStream cs;
        float y;

        Cursor(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = H - 105;
        }

        void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        float ensureSpace(float currentY, float needed) throws IOException {
            if (currentY - needed < M) {
                newPage();
                return H - 105;
            }
            return currentY;
        }
    }
}