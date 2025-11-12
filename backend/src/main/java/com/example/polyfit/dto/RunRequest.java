package com.example.polyfit.dto;

import com.example.polyfit.model.Point;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RunRequest(@NotNull @Valid GeneticConfig config,
                         @NotEmpty List<Point> points) {
}
