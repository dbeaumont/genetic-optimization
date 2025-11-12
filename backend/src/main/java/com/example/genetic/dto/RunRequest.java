package com.example.genetic.dto;

import com.example.genetic.model.Point;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RunRequest(@NotNull @Valid GeneticConfig config,
                         @NotEmpty List<Point> points) {
}
