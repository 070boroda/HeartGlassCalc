package by.greenmobile.heartglasscalc.entity;


import lombok.AllArgsConstructor;
import lombok.Data;


@Data
@AllArgsConstructor
public class CandidateDesign {
    private Double hexSide;
    private Double hexGap;

    private Double multiplier;
    private Double achievedResistance;
    private Double achievedPowerWm2;
    private Double deviationPercent;
}

