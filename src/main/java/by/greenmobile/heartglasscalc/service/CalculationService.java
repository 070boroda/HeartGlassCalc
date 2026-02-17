package by.greenmobile.heartglasscalc.service;

import by.greenmobile.heartglasscalc.entity.CandidateDesign;
import by.greenmobile.heartglasscalc.entity.GlassParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис расчёта электрических параметров и геометрии рисунка.
 * Все длины в мм, площади в м², мощности в Вт/м², сопротивления в Ом.
 */
@Service
@Slf4j
public class CalculationService {

    /** Напряжение сети, В. */
    private static final double VOLTAGE = 220.0;

    /** Минимальное число вертикальных проходов для зигзага. */
    private static final int MIN_ZIGZAG_SEGMENTS = 2;

    /** Ширина дорожки тока для зигзага, мм. */
    private static final double DEFAULT_TRACE_WIDTH_MM = 3.0;

    /** Сторона соты по умолчанию, мм. */
    private static final double DEFAULT_HEX_SIDE_MM = 30.0;

    /** Зазор между сотами по умолчанию, мм. */
    private static final double DEFAULT_HEX_GAP_MM = 2.0;

    /**
     * Эмпирический коэффициент, каким образом суммарный периметр сот
     * превращается в эффективную длину пути тока вдоль направления тока.
     */
    private static final double HONEYCOMB_PATH_COEFF = 0.35;

    /**
     * Главный метод: считает сопротивление, multiplier и геометрию рисунка.
     * Дополнительно вызывается computeEnergy(), которая заполняет энергетические поля.
     */
    public GlassParameters calculate(GlassParameters params) {
        if (params == null) {
            log.warn("calculate(): параметры = null");
            return null;
        }
        if (!params.hasValidInput()) {
            log.warn("calculate(): некорректные входные данные: {}", params);
            return params;
        }

        log.info("Старт расчёта. Входные параметры: {}", params);

        // Площадь стекла в м²
        double areaM2 = (params.getWidth() * params.getHeight()) / 1_000_000.0;
        log.debug("Площадь стекла: {} м²", areaM2);

        // Требуемое сопротивление для заданной мощности:
        // R_target = U^2 / (Pуд * S)
        double totalResistance = (VOLTAGE * VOLTAGE) /
                (params.getTargetPower() * areaM2);
        params.setTotalResistance(totalResistance);
        log.debug("Требуемое эффективное сопротивление R_target = {} Ом", totalResistance);

        // Базовое сопротивление слоя без рисунка:
        boolean verticalBusbars = params.isVerticalBusbars();

        double L = verticalBusbars ? params.getHeight() : params.getWidth();
        double W = verticalBusbars ? params.getWidth() : params.getHeight();

        double rawResistance = params.getSheetResistance() * (L / W);
        log.debug("Исходное сопротивление без рисунка R_raw ≈ {} Ом (L={} мм, W={} мм, ориентация шин: {})",
                rawResistance, L, W, verticalBusbars ? "вертикальная" : "горизонтальная");

        params.setRawResistance(rawResistance);

        // Коэффициент удлинения пути тока
        double multiplier = totalResistance / rawResistance;
        params.setPathLengthMultiplier(multiplier);
        log.info("Требуемый коэффициент удлинения пути тока: {}", multiplier);

        // Выбор режима рисунка
        if (params.isHoneycomb()) {
            log.info("Режим рисунка: соты (honeycomb)");
            calculateHoneycombGeometry(params, verticalBusbars);
        } else {
            log.info("Режим рисунка: зигзаг");
            calculateZigzagGeometry(params, verticalBusbars);
        }

        // ===== ФАКТИЧЕСКИЕ параметры по результату геометрии =====
// В honeycomb мы могли подправить multiplier (после округлений колонок).
// В zigzag multiplier = целевой, но оставим общую формулу.
        double areaM2After = (params.getWidth() * params.getHeight()) / 1_000_000.0;

        Double rr = params.getRawResistance();
        double multFact = params.getPathLengthMultiplier() != null ? params.getPathLengthMultiplier() : 0.0;

        if (rr != null && rr > 0 && multFact > 0) {
            double achievedR = rr * multFact;
            params.setAchievedResistance(achievedR);

            // фактическая мощность при 220В
            double achievedP = (VOLTAGE * VOLTAGE) / achievedR;
            params.setAchievedPowerWatts(achievedP);

            // удельная мощность по площади
            double achievedPwm2 = (areaM2After > 0) ? (achievedP / areaM2After) : 0.0;
            params.setAchievedPowerWm2(achievedPwm2);

            // отклонение от целевой удельной мощности
            double targetPwm2 = params.getTargetPower() != null ? params.getTargetPower() : 0.0;
            double devPct = (targetPwm2 > 0) ? ((achievedPwm2 - targetPwm2) / targetPwm2 * 100.0) : 0.0;
            params.setPowerDeviationPercent(devPct);

            log.info("Факт: R_raw={} Ом, mult_fact={}, R_ach={} Ом, P_ach={} Вт, P_ach_ud={} Вт/м², dev={}%",
                    rr, multFact, achievedR, achievedP, achievedPwm2, devPct);
        } else {
            params.setAchievedResistance(0.0);
            params.setAchievedPowerWatts(0.0);
            params.setAchievedPowerWm2(0.0);
            params.setPowerDeviationPercent(0.0);
        }


        // Энергетика
        computeEnergy(params);

        log.info("Расчёт завершён. Результат: {}", params);
        return params;
    }

    // ========================================================================
    // ЗИГЗАГ
    // ========================================================================

    private void calculateZigzagGeometry(GlassParameters params, boolean verticalBusbars) {
        double workingWidth = params.getWidth() - 2 * params.getEdgeOffset();
        double workingHeight = params.getHeight() - 2 * params.getEdgeOffset();

        log.debug("Зигзаг: рабочая зона {} x {} мм", workingWidth, workingHeight);

        if (workingWidth <= 0 || workingHeight <= 0) {
            log.warn("Зигзаг: рабочая зона некорректна, обнуляем параметры");
            params.setLineCount(0);
            params.setLineSpacing(0.0);
            params.setLineLength(0.0);
            return;
        }

        double traceWidth = DEFAULT_TRACE_WIDTH_MM;

        // Требуемая суммарная длина пути тока.
        double baseLength = verticalBusbars ? workingHeight : workingWidth;
        double requiredPathLength = baseLength * params.getPathLengthMultiplier();
        log.debug("Зигзаг: требуемая длина пути тока ~ {} мм (baseLength={})",
                requiredPathLength, baseLength);

        int verticalPasses = (int) Math.ceil(requiredPathLength / baseLength);
        if (verticalPasses < MIN_ZIGZAG_SEGMENTS) verticalPasses = MIN_ZIGZAG_SEGMENTS;

        double spacing = workingWidth / verticalPasses;
        int lineCount = Math.max(0, verticalPasses - 1);

        double lineLength = Math.max(0.0, workingHeight - 2 * traceWidth);

        params.setLineCount(lineCount);
        params.setLineSpacing(spacing);
        params.setLineLength(lineLength);

        log.info("Зигзаг: проходов={}, линий={}, шаг={} мм, длина линий={} мм",
                verticalPasses, lineCount, spacing, lineLength);
    }

    // ========================================================================
    // СОТЫ
    // ========================================================================

    /**
     * ВАЖНОЕ отличие от старой версии:
     * - rows/cols считаются через CEIL (и с небольшим запасом),
     *   чтобы сетка гарантированно перекрывала рабочую область.
     * - дальше multiplier подгоняется колонками, но rows мы не уменьшаем,
     *   чтобы не появлялись пустые полосы.
     *
     * Закрытие контуров на границе делается НЕ здесь, а в SVG/DXF генераторах
     * через клиппинг (обрезку полигона по рабочей зоне).
     */
    private void calculateHoneycombGeometry(GlassParameters params, boolean verticalBusbars) {
        double workingWidth = params.getWidth() - 2 * params.getEdgeOffset();
        double workingHeight = params.getHeight() - 2 * params.getEdgeOffset();

        log.debug("Соты: рабочая зона {} x {} мм", workingWidth, workingHeight);

        if (workingWidth <= 0 || workingHeight <= 0) {
            log.warn("Соты: рабочая зона некорректна, обнуляем параметры");
            params.setHexSide(0.0);
            params.setHexGap(0.0);
            params.setHexCols(0);
            params.setHexRows(0);
            return;
        }

        double a = (params.getHexSide() != null && params.getHexSide() > 0)
                ? params.getHexSide()
                : DEFAULT_HEX_SIDE_MM;

        double gap = (params.getHexGap() != null && params.getHexGap() >= 0)
                ? params.getHexGap()
                : DEFAULT_HEX_GAP_MM;

        double hexHeight = Math.sqrt(3.0) * a;

        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // CEIL вместо FLOOR + небольшой запас, чтобы сетка перекрывала область
        int rows = (int) Math.ceil(workingHeight / stepY) + 1;
        int cols = (int) Math.ceil(workingWidth / stepX) + 1;
        if (rows < 1) rows = 1;
        if (cols < 1) cols = 1;

        log.debug("Соты: первичная оценка cols={} rows={} (a={} мм, gap={} мм)", cols, rows, a, gap);

        int cellCount = cols * rows;
        double perimeterOneCell = 6.0 * a;
        double totalPerimeter = perimeterOneCell * cellCount;

        double directionLength = verticalBusbars ? workingHeight : workingWidth;

        double effectivePathLength = HONEYCOMB_PATH_COEFF * totalPerimeter;
        double estimatedMultiplier = effectivePathLength / directionLength;
        log.debug("Соты: первичный multiplier ≈ {}", estimatedMultiplier);

        double targetMultiplier = params.getPathLengthMultiplier();
        if (estimatedMultiplier > 0) {
            double scale = targetMultiplier / estimatedMultiplier;

            int scaledCols = (int) Math.max(1, Math.round(cols * scale));
            cols = scaledCols;

            cellCount = cols * rows;
            totalPerimeter = perimeterOneCell * cellCount;
            effectivePathLength = HONEYCOMB_PATH_COEFF * totalPerimeter;
            estimatedMultiplier = effectivePathLength / directionLength;

            log.debug("Соты: после подгонки cols={} -> новый multiplier ≈ {}", cols, estimatedMultiplier);
        }

        // Записываем фактический multiplier после округлений
        params.setPathLengthMultiplier(estimatedMultiplier);

        params.setHexSide(a);
        params.setHexGap(gap);
        params.setHexCols(cols);
        params.setHexRows(rows);

        // обнуляем зигзаговые параметры
        params.setLineCount(0);
        params.setLineSpacing(0.0);
        params.setLineLength(0.0);

        log.info("Соты: a={} мм, gap={} мм, cols={}, rows={}, cellCount={}, итоговый multiplier={}",
                a, gap, cols, rows, cellCount, estimatedMultiplier);
    }

    // ========================================================================
    // ЭНЕРГЕТИКА
    // ========================================================================

    private void computeEnergy(GlassParameters params) {
        double width = params.getWidth();
        double height = params.getHeight();
        double areaM2 = (width * height) / 1_000_000.0;

        double totalPower = params.getTargetPower() * areaM2;
        params.setTotalPowerWatts(totalPower);

        double ambient = params.getAmbientTemperature() != null
                ? params.getAmbientTemperature()
                : 0.0;

        double targetT;
        if (params.getTargetSurfaceTemperature() != null) {
            targetT = params.getTargetSurfaceTemperature();
        } else {
            int mode = params.getOperationMode() != null ? params.getOperationMode() : 1;
            switch (mode) {
                case 1: // конденсат
                    targetT = Math.max(ambient + 5.0, 10.0);
                    break;
                case 2: // отопление
                    targetT = Math.max(ambient + 10.0, 22.0);
                    break;
                case 3: // наледь/снег
                    targetT = Math.max(ambient + 10.0, 5.0);
                    break;
                default:
                    targetT = ambient + 10.0;
            }
        }

        double deltaT = targetT - ambient;
        if (deltaT <= 0 || totalPower <= 0) {
            params.setWarmupTimeSeconds(0.0);
            params.setWarmupEnergyKWh(0.0);
            params.setHoldingPowerWatts(totalPower);
            params.setHoldingEnergyPerHourKWh(totalPower / 1000.0);
            return;
        }

        double thicknessMm = params.getGlassThickness() != null
                ? params.getGlassThickness()
                : 4.0;

        double thicknessM = thicknessMm / 1000.0;

        double density = 2500.0;      // кг/м³
        double heatCapacity = 840.0;  // Дж/(кг·К)

        double volume = areaM2 * thicknessM;
        double mass = volume * density;
        double heatCapacityTotal = mass * heatCapacity;

        double efficiency;
        int mode = params.getOperationMode() != null ? params.getOperationMode() : 1;
        switch (mode) {
            case 1: efficiency = 0.6; break;
            case 2: efficiency = 0.5; break;
            case 3: efficiency = 0.4; break;
            default: efficiency = 0.5;
        }

        double effectivePower = totalPower * efficiency;

        double warmupTimeSeconds = heatCapacityTotal * deltaT / effectivePower;
        double warmupEnergyKWh = (totalPower * warmupTimeSeconds) / 3_600_000.0;

        params.setWarmupTimeSeconds(warmupTimeSeconds);
        params.setWarmupEnergyKWh(warmupEnergyKWh);

        double holdingPower = totalPower * (1.0 - efficiency);
        double holdingEnergyPerHourKWh = holdingPower / 1000.0;

        params.setHoldingPowerWatts(holdingPower);
        params.setHoldingEnergyPerHourKWh(holdingEnergyPerHourKWh);

        log.info(
                "Энергетика: P_total={} Вт, ΔT={} °C, t≈{} с (~{} мин), E_warmup≈{} кВт·ч, P_hold≈{} Вт, E_hold≈{} кВт·ч/ч",
                totalPower,
                deltaT,
                warmupTimeSeconds,
                warmupTimeSeconds / 60.0,
                warmupEnergyKWh,
                holdingPower,
                holdingEnergyPerHourKWh
        );
    }

    public List<CandidateDesign> findTopHoneycombDesigns(GlassParameters baseParams) {

        List<CandidateDesign> results = new ArrayList<>();

        double tolerancePercent = 10.0; // допустимое отклонение от цели

        // ===== Площадь =====
        double areaM2 = (baseParams.getWidth() * baseParams.getHeight()) / 1_000_000.0;

        // ===== Базовое сопротивление =====
        boolean verticalBusbars = baseParams.isVerticalBusbars();

        double L = verticalBusbars ? baseParams.getHeight() : baseParams.getWidth();
        double W = verticalBusbars ? baseParams.getWidth() : baseParams.getHeight();

        double R_raw = baseParams.getSheetResistance() * (L / W);

        double targetPower = baseParams.getTargetPower();

        for (double a = 15; a <= 60; a += 1.0) {
            for (double gap = 1; gap <= 8; gap += 0.5) {

                double multiplier = estimateMultiplier(baseParams, a, gap);

                double R_ach = R_raw * multiplier;

                if (R_ach <= 0) continue;

                double P_ach = (220.0 * 220.0) / R_ach;
                double P_wm2 = P_ach / areaM2;

                double deviationPercent =
                        (P_wm2 - targetPower) / targetPower * 100.0;

                // ФИЛЬТРУЕМ ТОЛЬКО ДОПУСТИМЫЕ РЕШЕНИЯ
                if (Math.abs(deviationPercent) <= tolerancePercent) {

                    results.add(new CandidateDesign(
                            a,
                            gap,
                            multiplier,
                            R_ach,
                            P_wm2,
                            deviationPercent
                    ));
                }
            }
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(
                        c -> Math.abs(c.getDeviationPercent())))
                .limit(5)
                .collect(Collectors.toList());
    }


    private double estimateMultiplier(GlassParameters params,
                                      double a,
                                      double gap) {

        double workingWidth =
                params.getWidth() - 2 * params.getEdgeOffset();
        double workingHeight =
                params.getHeight() - 2 * params.getEdgeOffset();

        double hexHeight = Math.sqrt(3.0) * a;

        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        if (stepX <= 0 || stepY <= 0) return 0;

        // НЕ используем ceil
        double densityX = workingWidth / stepX;
        double densityY = workingHeight / stepY;

        double cellCount = densityX * densityY;

        double totalPerimeter = 6.0 * a * cellCount;

        double directionLength =
                params.isVerticalBusbars()
                        ? workingHeight
                        : workingWidth;

        if (directionLength <= 0) return 0;

        return 0.35 * totalPerimeter / directionLength;
    }



}
