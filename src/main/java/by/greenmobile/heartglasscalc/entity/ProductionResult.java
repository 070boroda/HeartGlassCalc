package by.greenmobile.heartglasscalc.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ProductionResult {

    /** Топ вариантов (может быть пустым). */
    private List<CandidateDesign> designs;

    /** Можно ли попасть в цель в рамках диапазонов/ограничений. */
    private boolean achievable;

    /** Рекомендация (если недостижимо или если расширяли диапазон). */
    private String recommendation;

    /** Требуемый multiplier для попадания. */
    private Double requiredMultiplier;

    /** Максимально достижимый multiplier в текущих расширенных диапазонах. */
    private Double maxMultiplier;

    /** Максимально достижимая удельная мощность (Вт/м²) в текущих диапазонах. */
    private Double maxAchievablePowerWm2;
}

