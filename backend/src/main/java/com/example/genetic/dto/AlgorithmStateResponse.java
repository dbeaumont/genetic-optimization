package com.example.genetic.dto;

import com.example.genetic.model.GenerationSnapshot;
import java.util.List;

public record AlgorithmStateResponse(boolean running,
                                     GeneticConfig config,
                                     GenerationSnapshot latestGeneration,
                                     List<GenerationSnapshot> history) {
}
