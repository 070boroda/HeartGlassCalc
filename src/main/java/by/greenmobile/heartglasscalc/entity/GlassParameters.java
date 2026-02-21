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

    /** Целевая удельная мощность, Вт/м². В текущих расчётах трактуется как Вт/м² по АКТИВНОЙ зоне между шинами. */
    private Double targetPower;

    /** Удельное сопротивление покрытия (sheet resistance), Ом/квадрат (Ом/□). */
    private Double sheetResistance;

    /** Отступ от кромки стекла до рабочей зоны рисунка, мм. */
    private Double edgeOffset;

    /** Ширина токопроводящей шины, мм. */
    private Double busbarWidth;

    /**
     * Тип рисунка:
     * 1 - зигзаг (вертикальные прорези),
     * 2 - соты (гексагональная решётка).
     */
    private Integer patternType;

    /**
     * Ориентация шин:
     * 1 - шины сверху/снизу (ток по высоте) — режим по умолчанию,
     * 2 - шины слева/справа (ток по ширине).
     */
    private Integer busbarOrientation;

    /**
     * Режим работы:
     * 1 - удаление конденсата,
     * 2 - отопление помещения,
     * 3 - удаление наледи / снег.
     */
    private Integer operationMode;

    /** Толщина стекла, мм (для оценки массы и тепловой инерции). */
    private Double glassThickness;

    /** Температура окружающего воздуха, °C. */
    private Double ambientTemperature;

    /** Целевая температура поверхности стекла, °C. */
    private Double targetSurfaceTemperature;

    // ===== ДОП. ПАРАМЕТРЫ ДЛЯ СОТ =====

    /** Сторона шестиугольной "островковой" соты (hex island), мм. */
    private Double hexSide;

    /**
     * ВАЖНО про hexGap:
     * - Для honeycomb.physical.pattern=ISLANDS (твой случай): hexGap = ширина ПРОВОДЯЩЕЙ дорожки/канала между островками (мм).
     * - Для pattern=LINES: hexGap может трактоваться как ширина ПРОЖИГА/канавки (мм).
     *
     * Если перепутать смысл gap — расчёт будет ехать на порядки.
     */
    private Double hexGap;

    /** Число колонок сот по ширине рабочей зоны (расчётное). */
    private Integer hexCols;

    /** Число рядов сот по высоте рабочей зоны (расчётное). */
    private Integer hexRows;

    // ===== РАСЧЁТНЫЕ ПОЛЯ (общие) =====

    /** Целевое сопротивление между шинами для заданной удельной мощности, Ом. */
    private Double totalResistance;

    /** Коэффициент увеличения сопротивления/пути тока относительно сплошного покрытия. */
    private Double pathLengthMultiplier;

    /** Количество линий абляции (для зигзага). */
    private Integer lineCount;

    /** Шаг между линиями абляции (для зигзага), мм. */
    private Double lineSpacing;

    /** Длина линий абляции (для зигзага), мм. */
    private Double lineLength;

    // ===== РАСЧЁТНЫЕ ПОЛЯ ДЛЯ ЭНЕРГЕТИКИ =====

    /** Полная электрическая мощность стекла, Вт. */
    private Double totalPowerWatts;

    /** Время разогрева до целевой температуры, секунд. */
    private Double warmupTimeSeconds;

    /** Энергия на разогрев до целевой, кВт·ч. */
    private Double warmupEnergyKWh;

    /** Мощность, необходимая для поддержания температуры, Вт (оценка). */
    private Double holdingPowerWatts;

    /** Энергия за час поддержания температуры, кВт·ч. */
    private Double holdingEnergyPerHourKWh;

    // ===== “что получилось” =====

    /** Базовое сопротивление слоя без рисунка между шинами (по активной геометрии), Ом. */
    private Double rawResistance;

    /** Сопротивление, получившееся с учётом multiplier, Ом. */
    private Double achievedResistance;

    /** Мощность, которая получится при 220В и achievedResistance, Вт. */
    private Double achievedPowerWatts;

    /** Удельная мощность по активной площади, Вт/м². */
    private Double achievedPowerWm2;

    /** Отклонение от целевой удельной мощности, %. */
    private Double powerDeviationPercent;

    /** Зазор от рабочей зоны рисунка до шины (не рисуем вплотную к шине), мм. */
    private Double busbarClearanceMm;

    // ===== УТИЛИТЫ =====

    /** Базовая проверка входных данных, чтобы не ловить деление на ноль. */
    public boolean hasValidInput() {
        return width != null && width > 0
                && height != null && height > 0
                && targetPower != null && targetPower > 0
                && sheetResistance != null && sheetResistance > 0
                && edgeOffset != null && edgeOffset >= 0
                && busbarWidth != null && busbarWidth > 0
                && edgeOffset * 2 < width
                && edgeOffset * 2 < height;
    }

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