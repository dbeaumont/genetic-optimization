package com.example.polyfit.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GeneticConfig(
        @NotNull @Min(10) @Max(5000) int populationSize,
        @NotNull @Min(1) @Max(10000) int maxGenerations,
        @NotNull @Min(1) @Max(1000) long iterationDelayMs,
        @NotNull @Min(0) @Max(100) double mutationRate,
        @NotNull @Min(0) @Max(100) double crossoverRate,
        @NotNull @Min(0) @Max(100) double elitismRate,
        @NotNull @Min(0) double alignmentTolerance,
        @NotNull @Min(0) double explorationRange
) {
    public GeneticConfig {
        if (mutationRate + crossoverRate <= 0) {
            throw new IllegalArgumentException("Mutation + crossover rate must be > 0");
        }
    }
}
