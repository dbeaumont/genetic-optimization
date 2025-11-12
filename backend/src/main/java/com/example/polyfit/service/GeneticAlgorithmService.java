package com.example.polyfit.service;

import com.example.polyfit.dto.GeneticConfig;
import com.example.polyfit.dto.RunRequest;
import com.example.polyfit.model.GenerationSnapshot;
import com.example.polyfit.model.Point;
import com.example.polyfit.model.PolynomialSolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class GeneticAlgorithmService {

    private static final int COEFFICIENT_COUNT = 8;
    private static final int CURVE_RESOLUTION = 200;
    private static final double EPSILON = 1e-6;
    private static final int MAX_HISTORY = 200;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<GenerationSnapshot> history = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper;

    private volatile GeneticConfig config;
    private volatile List<Point> points = List.of();
    private volatile Future<?> currentTask;
    private volatile GenerationSnapshot latestSnapshot;

    public GeneticAlgorithmService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void start(RunRequest request) {
        stop();
        this.config = request.config();
        this.points = List.copyOf(request.points());
        this.history.clear();
        running.set(true);
        currentTask = executor.submit(this::runAlgorithm);
    }

    public synchronized void stop() {
        running.set(false);
        if (currentTask != null) {
            currentTask.cancel(true);
        }
    }

    public synchronized void reset() {
        stop();
        history.clear();
        latestSnapshot = null;
        config = null;
        points = List.of();
    }

    public boolean isRunning() {
        return running.get();
    }

    public Optional<GenerationSnapshot> latestSnapshot() {
        return Optional.ofNullable(latestSnapshot);
    }

    public List<GenerationSnapshot> history() {
        return history;
    }

    public GeneticConfig getConfig() {
        return config;
    }

    public SseEmitter connectStream() {
        SseEmitter emitter = new SseEmitter(0L);
        this.emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        latestSnapshot().ifPresent(snapshot -> safeSend(emitter, snapshot));
        return emitter;
    }

    private void runAlgorithm() {
        try {
            if (points.isEmpty() || config == null) {
                running.set(false);
                return;
            }
            int populationSize = config.populationSize();
            double[][] population = initializePopulation(populationSize, config.explorationRange());
            double[] fitnesses = new double[populationSize];

            for (int generation = 1; generation <= config.maxGenerations() && running.get(); generation++) {
                evaluatePopulation(population, fitnesses);
                GenerationSnapshot snapshot = buildSnapshot(generation, population, fitnesses);
                persistSnapshot(snapshot);
                sendToEmitters(snapshot);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                population = nextGeneration(population, fitnesses);
                try {
                    Thread.sleep(Math.max(1, config.iterationDelayMs()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            running.set(false);
        }
    }

    private double[][] initializePopulation(int populationSize, double range) {
        double[][] population = new double[populationSize][COEFFICIENT_COUNT];
        for (int i = 0; i < populationSize; i++) {
            for (int j = 0; j < COEFFICIENT_COUNT; j++) {
                population[i][j] = (Math.random() * 2 - 1) * range;
            }
        }
        return population;
    }

    private void evaluatePopulation(double[][] population, double[] fitnesses) {
        for (int i = 0; i < population.length; i++) {
            fitnesses[i] = fitness(population[i]);
        }
    }

    private GenerationSnapshot buildSnapshot(int generation, double[][] population, double[] fitnesses) {
        List<PolynomialSolution> solutions = new ArrayList<>(population.length);
        for (int i = 0; i < population.length; i++) {
            solutions.add(buildSolution(population[i], fitnesses[i]));
        }
        solutions.sort(Comparator.comparingDouble(PolynomialSolution::fitness).reversed());
        PolynomialSolution bestSolution = solutions.get(0);
        List<PolynomialSolution> top = solutions.stream().limit(5).collect(Collectors.toList());
        List<Point> curve = buildCurvePoints(bestSolution.coefficients());
        GenerationSnapshot snapshot = new GenerationSnapshot(generation, bestSolution, top, Instant.now(), curve);
        latestSnapshot = snapshot;
        return snapshot;
    }

    private PolynomialSolution buildSolution(double[] coefficients, double fitness) {
        int covered = 0;
        double error = 0;
        for (Point point : points) {
            double diff = evaluateFunction(coefficients, point.x()) - point.y();
            error += diff * diff;
            if (Math.abs(diff) <= config.alignmentTolerance()) {
                covered++;
            }
        }
        double[] snapshot = Arrays.copyOf(coefficients, coefficients.length);
        String expression = expressionFor(snapshot);
        return new PolynomialSolution(snapshot, fitness, covered, error, expression);
    }

    private void persistSnapshot(GenerationSnapshot snapshot) {
        history.add(snapshot);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private void sendToEmitters(GenerationSnapshot snapshot) {
        for (SseEmitter emitter : emitters) {
            safeSend(emitter, snapshot);
        }
    }

    private void safeSend(SseEmitter emitter, GenerationSnapshot snapshot) {
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            emitter.send(payload);
        } catch (IOException e) {
            emitter.completeWithError(e);
            emitters.remove(emitter);
        }
    }

    private double[][] nextGeneration(double[][] population, double[] fitnesses) {
        int populationSize = population.length;
        double[][] next = new double[populationSize][COEFFICIENT_COUNT];
        int elitismCount = Math.max(1, (int) Math.round(populationSize * (config.elitismRate() / 100.0)));
        Integer[] indices = new Integer[populationSize];
        for (int i = 0; i < populationSize; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, Comparator.comparingDouble((Integer idx) -> fitnesses[idx]).reversed());
        for (int i = 0; i < elitismCount; i++) {
            next[i] = Arrays.copyOf(population[indices[i]], COEFFICIENT_COUNT);
        }
        for (int i = elitismCount; i < populationSize; i++) {
            double[] parent1 = tournamentSelect(population, fitnesses);
            double[] parent2 = tournamentSelect(population, fitnesses);
            double[] child = crossover(parent1, parent2);
            mutate(child);
            next[i] = child;
        }
        return next;
    }

    private double[] tournamentSelect(double[][] population, double[] fitnesses) {
        int tournamentSize = Math.max(2, (int) Math.round(population.length * 0.05));
        double[] best = null;
        double bestFitness = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tournamentSize; i++) {
            int idx = (int) (Math.random() * population.length);
            if (fitnesses[idx] > bestFitness) {
                bestFitness = fitnesses[idx];
                best = population[idx];
            }
        }
        return Arrays.copyOf(best, COEFFICIENT_COUNT);
    }

    private double[] crossover(double[] parent1, double[] parent2) {
        double rate = config.crossoverRate() / 100.0;
        if (Math.random() > rate) {
            return Arrays.copyOf(parent1, parent1.length);
        }
        double[] child = new double[parent1.length];
        int point = 1 + (int) (Math.random() * (parent1.length - 1));
        for (int i = 0; i < parent1.length; i++) {
            child[i] = i < point ? parent1[i] : parent2[i];
        }
        return child;
    }

    private void mutate(double[] chromosome) {
        double rate = config.mutationRate() / 100.0;
        double range = config.explorationRange();
        for (int i = 0; i < chromosome.length; i++) {
            if (Math.random() < rate) {
                double delta = (Math.random() * 2 - 1) * (range * 0.1);
                chromosome[i] += delta;
            }
        }
    }

    private double fitness(double[] chromosome) {
        int covered = 0;
        double error = 0;
        for (Point point : points) {
            double diff = evaluateFunction(chromosome, point.x()) - point.y();
            error += diff * diff;
            if (Math.abs(diff) <= config.alignmentTolerance()) {
                covered++;
            }
        }
        return covered * 1000 - error;
    }

    private double evaluateFunction(double[] chromosome, double x) {
        double base = chromosome[0]
                + chromosome[1] * x
                + chromosome[2] * Math.pow(x, 2)
                + chromosome[3] * Math.pow(x, 3);
        double absTerm = chromosome[4] * Math.abs(x);
        double sqrtTerm = chromosome[5] * Math.sqrt(Math.abs(x));
        double expInput = Math.max(-10, Math.min(10, x)); // clamp to avoid overflow
        double expTerm = chromosome[6] * Math.exp(expInput);
        double denom = 1.0 + Math.abs(chromosome[7]);
        double value = (base + absTerm + sqrtTerm + expTerm) / denom;
        if (!Double.isFinite(value)) {
            return 0;
        }
        return value;
    }

    private String expressionFor(double[] coefficients) {
        String c0 = formatCoeff(coefficients[0]);
        String c1 = formatCoeff(coefficients[1]);
        String c2 = formatCoeff(coefficients[2]);
        String c3 = formatCoeff(coefficients[3]);
        String c4 = formatCoeff(coefficients[4]);
        String c5 = formatCoeff(coefficients[5]);
        String c6 = formatCoeff(coefficients[6]);
        String c7 = formatCoeff(coefficients[7]);
        return "((" + c0 + " + " + c1 + "·x + " + c2 + "·x^2 + " + c3 + "·x^3) + "
                + c4 + "·|x| + " + c5 + "·sqrt(|x|) + " + c6 + "·exp(x)) / (1 + |" + c7 + "|)";
    }

    private String formatCoeff(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private List<Point> buildCurvePoints(double[] coefficients) {
        double minX = points.stream().mapToDouble(Point::x).min().orElse(-5d);
        double maxX = points.stream().mapToDouble(Point::x).max().orElse(5d);
        if (Math.abs(maxX - minX) < 1e-3) {
            maxX = minX + 10;
            minX -= 10;
        }
        double step = (maxX - minX) / (CURVE_RESOLUTION - 1);
        List<Point> curve = new ArrayList<>(CURVE_RESOLUTION);
        for (int i = 0; i < CURVE_RESOLUTION; i++) {
            double x = minX + step * i;
            double y = evaluateFunction(coefficients, x);
            if (!Double.isFinite(y)) {
                continue;
            }
            curve.add(new Point(x, y));
        }
        return curve;
    }
}
