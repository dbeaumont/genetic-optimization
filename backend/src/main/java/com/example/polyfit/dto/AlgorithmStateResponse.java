package com.example.polyfit.dto;

import com.example.polyfit.model.GenerationSnapshot;
import java.util.List;

public record AlgorithmStateResponse(boolean running,
                                     GeneticConfig config,
                                     GenerationSnapshot latestGeneration,
                                     List<GenerationSnapshot> history) {
}
