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
 *
 * ВАЖНО:
 * - Этот сервис НЕ считает «фактические» R_ach / P_ach и отклонения.
 *   Это делает только EngineeringFacade через solver.
 * - Здесь считаются:
 *   R_target, R_raw, multiplier-оценка и геометрия (зигзаг/соты), плюс энергетика.
 *
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

        // Коэффициент удлинения пути тока (первичная оценка)
        double multiplier = totalResistance / rawResistance;
        params.setPathLengthMultiplier(multiplier);
        log.info("Требуемый коэффициент удлинения пути тока (оценка): {}", multiplier);

        // Выбор режима рисунка
        if (params.isHoneycomb()) {
            log.info("Режим рисунка: соты (honeycomb)");
            calculateHoneycombGeometry(params, verticalBusbars);
        } else {
            log.info("Режим рисунка: зигзаг");
            calculateZigzagGeometry(params, verticalBusbars);
        }

        // Энергетика
        computeEnergy(params);

        log.info("Расчёт (без solver) завершён. Результат: {}", params);
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
     * Геометрия сот должна соответствовать тому, как она будет клипаться в SVG/DXF/solver:
     * рабочая зона = стекло минус edgeOffset и минус (busbarWidth + clearance) около шин.
     *
     * Важно:
     * - rows/cols считаются через CEIL + запас, чтобы сетка гарантированно перекрывала clip-область.
     * - cols можно подгонять под multiplier, НО нельзя уменьшать cols ниже минимума покрытия (colsMin)
     *   иначе появляются пустые полосы.
     */
    private void calculateHoneycombGeometry(GlassParameters params, boolean verticalBusbars) {
        double width = params.getWidth();
        double height = params.getHeight();

        double edge = Math.max(0.0, params.getEdgeOffset());
        double busW = Math.max(0.0, params.getBusbarWidth());

        double a = (params.getHexSide() != null && params.getHexSide() > 0)
                ? params.getHexSide()
                : DEFAULT_HEX_SIDE_MM;

        double gap = (params.getHexGap() != null && params.getHexGap() >= 0)
                ? params.getHexGap()
                : DEFAULT_HEX_GAP_MM;

        // Зазор до шины: если null -> gap (так же как в SVG/DXF/solver)
        double clearance = (params.getBusbarClearanceMm() != null)
                ? Math.max(0.0, params.getBusbarClearanceMm())
                : Math.max(0.0, gap);

        if (width <= 2 * edge || height <= 2 * edge) {
            log.warn("Соты: edgeOffset слишком большой, рабочая зона <=0");
            params.setHexSide(0.0);
            params.setHexGap(0.0);
            params.setHexCols(0);
            params.setHexRows(0);
            return;
        }

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        // Clip-область (как в SVG/DXF): edgeOffset + busbarWidth + clearance
        double clipLeft = edge;
        double clipRight = width - edge;
        double clipTop = edge;
        double clipBottom = height - edge;

        if (verticalBusbars) {
            clipTop = edge + busW + clearance;
            clipBottom = height - edge - busW - clearance;
        } else {
            clipLeft = edge + busW + clearance;
            clipRight = width - edge - busW - clearance;
        }

        double availW = Math.max(0.0, clipRight - clipLeft);
        double availH = Math.max(0.0, clipBottom - clipTop);

        log.debug("Соты: clipRect=[{},{}]-[{},{}], avail={}x{} мм (a={}, gap={}, clearance={}, ориентация={})",
                clipLeft, clipTop, clipRight, clipBottom, availW, availH, a, gap, clearance,
                verticalBusbars ? "верх/низ" : "лево/право");

        if (availW <= 0 || availH <= 0) {
            log.warn("Соты: clip-область некорректна (availW/availH<=0), обнуляем параметры");
            params.setHexSide(0.0);
            params.setHexGap(0.0);
            params.setHexCols(0);
            params.setHexRows(0);
            return;
        }

        // Минимум, чтобы гарантированно перекрыть clip-область.
        int rowsMin = (int) Math.ceil(availH / stepY) + 1;
        int colsMin = (int) Math.ceil(availW / stepX) + 1;
        rowsMin = Math.max(1, rowsMin);
        colsMin = Math.max(1, colsMin);

        int rows = rowsMin;
        int cols = colsMin;

        int cellCount = cols * rows;
        double perimeterOneCell = 6.0 * a;
        double totalPerimeter = perimeterOneCell * cellCount;

        double directionLength = verticalBusbars ? availH : availW;
        directionLength = Math.max(directionLength, 1e-9);

        double effectivePathLength = HONEYCOMB_PATH_COEFF * totalPerimeter;
        double estimatedMultiplier = effectivePathLength / directionLength;
        log.debug("Соты: первичный multiplier ≈ {} (colsMin={}, rowsMin={})", estimatedMultiplier, colsMin, rowsMin);

        // Подгоняем cols под целевой multiplier, но не ниже colsMin
        double targetMultiplier = params.getPathLengthMultiplier();
        if (estimatedMultiplier > 0 && targetMultiplier > 0) {
            double scale = targetMultiplier / estimatedMultiplier;

            int scaledCols = (int) Math.max(1, Math.round(cols * scale));
            cols = Math.max(colsMin, scaledCols);

            cellCount = cols * rows;
            totalPerimeter = perimeterOneCell * cellCount;
            effectivePathLength = HONEYCOMB_PATH_COEFF * totalPerimeter;
            estimatedMultiplier = effectivePathLength / directionLength;

            log.debug("Соты: после подгонки cols={} (scaledCols={}) -> новый multiplier ≈ {}",
                    cols, scaledCols, estimatedMultiplier);
        }

        // Пишем оценочный multiplier (факт даст solver)
        params.setPathLengthMultiplier(estimatedMultiplier);

        params.setHexSide(a);
        params.setHexGap(gap);
        params.setHexCols(cols);
        params.setHexRows(rows);

        // обнуляем зигзаговые параметры
        params.setLineCount(0);
        params.setLineSpacing(0.0);
        params.setLineLength(0.0);

        log.info("Соты: a={} мм, gap={} мм, clearance={} мм, cols={}, rows={}, cellCount={}, estMultiplier={}",
                a, gap, clearance, cols, rows, cellCount, estimatedMultiplier);
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

    /**
     * Грубая оценка multiplier для перебора кандидатов (не для финального результата!).
     */
    private double estimateMultiplier(GlassParameters baseParams, double a, double gap) {
        boolean verticalBusbars = baseParams.isVerticalBusbars();

        double width = baseParams.getWidth();
        double height = baseParams.getHeight();
        double edge = Math.max(0.0, baseParams.getEdgeOffset());

        // Для быстрого перебора можно использовать простую рабочую область по edgeOffset
        double workingWidth = width - 2 * edge;
        double workingHeight = height - 2 * edge;
        if (workingWidth <= 0 || workingHeight <= 0) return 0.0;

        double hexHeight = Math.sqrt(3.0) * a;
        double stepX = 1.5 * a + gap;
        double stepY = hexHeight + gap;

        int rows = (int) Math.ceil(workingHeight / stepY) + 1;
        int cols = (int) Math.ceil(workingWidth / stepX) + 1;
        rows = Math.max(1, rows);
        cols = Math.max(1, cols);

        int cellCount = rows * cols;
        double totalPerimeter = 6.0 * a * cellCount;

        double directionLength = verticalBusbars ? workingHeight : workingWidth;
        directionLength = Math.max(directionLength, 1e-9);

        double effPath = HONEYCOMB_PATH_COEFF * totalPerimeter;
        return effPath / directionLength;
    }
}
