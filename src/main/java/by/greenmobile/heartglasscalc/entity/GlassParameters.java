package by.greenmobile.heartglasscalc.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Параметры стекла и расчётные значения.
 *
 * Единицы:
 * - длины: мм
 * - площадь: м²
 * - мощность: Вт/м² (удельная) и Вт (суммарная)
 * - сопротивления: Ом
 * - sheetResistance: Ом/квадрат (Ом/□)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlassParameters {

    // ===== ВХОДНЫЕ ПАРАМЕТРЫ (общие) =====

    /** Ширина стекла, мм (по горизонтали). */
    private Double width;

    /** Высота стекла, мм (по вертикали). */
    private Double height;

    /** Целевая удельная мощность, Вт/м². Трактуется как Вт/м² по АКТИВНОЙ зоне между шинами. */
    private Double targetPower;

    /** Удельное сопротивление покрытия (sheet resistance), Ом/квадрат (Ом/□). */
    private Double sheetResistance;

    /** Отступ от кромки стекла до рабочей зоны рисунка, мм. */
    private Double edgeOffset;

    /** Ширина токопроводящей шины, мм. */
    private Double busbarWidth;

    /**
     * Тип рисунка:
     * 1 - зигзаг,
     * 2 - соты (гексагональная решётка).
     */
    private Integer patternType;

    /**
     * Ориентация шин:
     * 1 - шины сверху/снизу (ток по высоте) — режим по умолчанию,
     * 2 - шины слева/справа (ток по ширине).
     */
    private Integer busbarOrientation;

    /** Толщина стекла, мм (опционально). */
    private Double glassThickness;

    /** Температура окружающего воздуха, °C (опционально). */
    private Double ambientTemperature;

    /** Целевая температура поверхности, °C (опционально). */
    private Double targetSurfaceTemperature;

    /** Зазор от рабочей зоны рисунка до шины, мм. */
    private Double busbarClearanceMm;

    // ===== ДОП. ПАРАМЕТРЫ ДЛЯ СОТ =====

    /** Сторона шестиугольной "островковой" соты (hex island), мм. */
    private Double hexSide;

    /**
     * ВАЖНО про hexGap:
     * - Для honeycomb.physical.pattern=ISLANDS: hexGap = ширина ПРОВОДЯЩЕЙ дорожки/канала между островками (мм).
     * - Для pattern=LINES: hexGap может трактоваться как ширина ПРОЖИГА/канавки (мм).
     */
    private Double hexGap;

    /** Число колонок сот по ширине рабочей зоны (расчётное). */
    private Integer hexCols;

    /** Число рядов сот по высоте рабочей зоны (расчётное). */
    private Integer hexRows;

    // ===== РАСЧЁТНЫЕ ПОЛЯ =====

    /** Целевое сопротивление между шинами для заданной удельной мощности, Ом. */
    private Double totalResistance;

    /** Коэффициент увеличения сопротивления/пути тока относительно сплошного покрытия. */
    private Double pathLengthMultiplier;

    /** Базовое сопротивление слоя без рисунка между шинами (по активной геометрии), Ом. */
    private Double rawResistance;

    /** Сопротивление, получившееся с учётом multiplier, Ом. */
    private Double achievedResistance;

    /** Суммарная мощность при 220В и achievedResistance, Вт. */
    private Double achievedPowerWatts;

    /** Удельная мощность по активной площади, Вт/м². */
    private Double achievedPowerWm2;

    /** Отклонение от целевой удельной мощности, %. */
    private Double powerDeviationPercent;

    // ===== КАЛИБРОВКА (НОВОЕ) =====

    /**
     * Измеренное сопротивление на реальном образце (R_fact), Ом.
     * Используется только для режима калибровки. Если null/<=0 — калибровка не выполняется.
     */
    private Double measuredResistance;

    /**
     * Рекомендованный scale для honeycomb.multiplier.scale:
     * scale = R_fact / R_calc (где R_calc = achievedResistance при текущей модели).
     */
    private Double recommendedMultiplierScale;

    /** Расхождение calc vs fact, %: (R_calc - R_fact)/R_fact*100. */
    private Double calibrationErrorPercent;

    // ===== УТИЛИТЫ =====

    public boolean isHoneycomb() {
        return patternType != null && patternType == 2;
    }

    /**
     * true, если шины сверху/снизу (ток по высоте).
     * Название историческое: не путать с "вертикальные шины".
     */
    public boolean isVerticalBusbars() {
        return busbarOrientation == null || busbarOrientation == 1;
    }
}