package com.example.genetic.controller;

import com.example.genetic.dto.AlgorithmStateResponse;
import com.example.genetic.dto.GeneticConfig;
import com.example.genetic.dto.RunRequest;
import com.example.genetic.service.GeneticAlgorithmService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/algorithm")
@CrossOrigin
public class AlgorithmController {

    private final GeneticAlgorithmService algorithmService;

    public AlgorithmController(GeneticAlgorithmService algorithmService) {
        this.algorithmService = algorithmService;
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start(@RequestBody @Valid RunRequest request) {
        algorithmService.start(request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        algorithmService.stop();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        algorithmService.reset();
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/state")
    public AlgorithmStateResponse state() {
        return new AlgorithmStateResponse(
                algorithmService.isRunning(),
                algorithmService.getConfig(),
                algorithmService.latestSnapshot().orElse(null),
                algorithmService.history()
        );
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return algorithmService.connectStream();
    }

    @GetMapping("/default-config")
    public GeneticConfig defaultConfig() {
        return new GeneticConfig(150, 2000, 100, 20, 70, 10, 0.2, 10);
    }
}
