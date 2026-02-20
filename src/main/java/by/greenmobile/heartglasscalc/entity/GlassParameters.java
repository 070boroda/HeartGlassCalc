package by.greenmobile.heartglasscalc.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Параметры стекла и расчётные значения.
 * Все длины в мм, площади в м², мощности в Вт/м², сопротивления в Ом.
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

    /** Целевая удельная мощность по поверхности, Вт/м². */
    private Double targetPower;

    /** Удельное сопротивление покрытия, Ом/квадрат. */
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

    /** Сторона шестиугольной ячейки, мм. */
    private Double hexSide;

    /** Зазор между сотами (просвет/ширина абляции), мм. */
    private Double hexGap;

    /** Число колонок сот по ширине рабочей зоны (расчётное). */
    private Integer hexCols;

    /** Число рядов сот по высоте рабочей зоны (расчётное). */
    private Integer hexRows;

    // ===== РАСЧЁТНЫЕ ПОЛЯ (общие) =====

    /** Эффективное сопротивление участка между шинами, Ом. */
    private Double totalResistance;

    /** Коэффициент удлинения пути тока относительно прямого пути. */
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

    // ===== Доп. вывод "что получилось" =====

    // базовое сопротивление слоя без рисунка (Ом)
    private Double rawResistance;

    // сопротивление, которое получилось с учётом фактического multiplier (Ом)
    private Double achievedResistance;

    // мощность, которая получится при 220В и achievedResistance (Вт)
    private Double achievedPowerWatts;

    // удельная мощность по площади (Вт/м²)
    private Double achievedPowerWm2;

    // отклонение от целевой удельной мощности (%)
    private Double powerDeviationPercent;

    //Отступ от шины
    private Double busbarClearanceMm;


    /**
     * Шаг сетки solver (dx=dy), мм.
     * null => по умолчанию 2.0 мм.
     *
     * Идея: для AUTO можно ставить 3–4 мм (быстрее), а для финального/manual — 2 мм (точнее).
     */
    private Double solverMeshStepMm;


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


    /** true, если шины сверху/снизу (ток по высоте). */
    public boolean isVerticalBusbars() {
        return busbarOrientation == null || busbarOrientation == 1;
    }
}
