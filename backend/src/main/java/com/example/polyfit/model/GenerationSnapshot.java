package com.example.polyfit.model;

import java.time.Instant;
import java.util.List;

public record GenerationSnapshot(int generation,
                                 PolynomialSolution bestSolution,
                                 List<PolynomialSolution> topSolutions,
                                 Instant timestamp,
                                 List<Point> curvePoints) { }
