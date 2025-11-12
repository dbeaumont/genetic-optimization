package com.example.polyfit.service;

import com.example.polyfit.dto.GeneticConfig;
import com.example.polyfit.dto.RunRequest;
import com.example.polyfit.model.GenerationSnapshot;
import com.example.polyfit.model.Point;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneticAlgorithmServiceTest {

    private GeneticAlgorithmService service;
    private List<Point> samplePoints;

    @BeforeEach
    void setUp() {
        service = new GeneticAlgorithmService(new ObjectMapper());
        samplePoints = List.of(
                new Point(-2, -4),
                new Point(-1, -1),
                new Point(0, 0),
                new Point(1, 2),
                new Point(2, 4),
                new Point(3, 9)
        );
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void startShouldProduceSnapshots() {
        GeneticConfig config = new GeneticConfig(
                30,         // population
                40,         // max generations
                1,        // delay
                15,           // mutation
                70,          // crossover
                5,             // elitism
                0.5,    // tolerance
                5         // exploration
        );

        service.start(new RunRequest(config, samplePoints));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !service.isRunning());

        assertThat(service.history()).isNotEmpty();
        assertThat(service.latestSnapshot()).isPresent();
        GenerationSnapshot snapshot = service.latestSnapshot().orElseThrow();
        assertThat(snapshot.bestSolution().coefficients().length).isEqualTo(6);
        assertThat(snapshot.bestSolution().pointsCovered()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.bestSolution().expression()).isNotBlank();
        assertThat(snapshot.curvePoints()).isNotEmpty();
    }

    @Test
    void stopShouldInterruptRun() {
        GeneticConfig config = new GeneticConfig(
                40,
                5_000,
                50,
                20,
                75,
                10,
                0.3,
                10
        );

        service.start(new RunRequest(config, samplePoints));

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(service::isRunning);

        service.stop();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !service.isRunning());

        assertThat(service.history().size()).isLessThanOrEqualTo(config.maxGenerations());
    }
}
