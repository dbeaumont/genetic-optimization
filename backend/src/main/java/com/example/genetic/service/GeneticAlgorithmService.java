package com.example.genetic.service;

import com.example.genetic.dto.GeneticConfig;
import com.example.genetic.dto.RunRequest;
import com.example.genetic.model.GenerationSnapshot;
import com.example.genetic.model.Point;
import com.example.genetic.model.PolynomialSolution;
import com.example.genetic.model.expression.BinaryNode;
import com.example.genetic.model.expression.BinaryOperator;
import com.example.genetic.model.expression.ConstantNode;
import com.example.genetic.model.expression.ExpressionNode;
import com.example.genetic.model.expression.UnaryNode;
import com.example.genetic.model.expression.UnaryOperator;
import com.example.genetic.model.expression.VariableNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Orchestrates the full lifecycle of the genetic search:
 *  - reacts to start/stop/reset commands,
 *  - streams incremental snapshots via SSE,
 *  - evolves expression trees that approximate the provided points.
 */
@Service
public class GeneticAlgorithmService {

    private static final int CURVE_RESOLUTION = 200;
    private static final double EPSILON = 1e-6;
    private static final int MAX_HISTORY = 200;
    private static final int MIN_TREE_DEPTH = 2;
    private static final int MAX_TREE_DEPTH = 5;
    private static final int MAX_NODE_COUNT = 50;
    private static final double CONSTANT_MIN = -10d;
    private static final double CONSTANT_MAX = 10d;

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

    // -------------------------------------------------------------------------
    // Lifecycle & state control
    // -------------------------------------------------------------------------

    /**
     * Starts a new run with the provided configuration and working set of points.
     */
    public synchronized void start(RunRequest request) {
        stop();
        this.config = request.config();
        this.points = List.copyOf(request.points());
        this.history.clear();
        running.set(true);
        currentTask = executor.submit(this::runAlgorithm);
    }

    /**
     * Attempts to halt the running worker thread.
     */
    public synchronized void stop() {
        running.set(false);
        if (currentTask != null) {
            currentTask.cancel(true);
        }
    }

    /**
     * Stops the worker and clears any cached state (history/config/points).
     */
    public synchronized void reset() {
        stop();
        history.clear();
        latestSnapshot = null;
        config = null;
        points = List.of();
    }

    @PreDestroy
    void shutdownExecutor() {
        running.set(false);
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        executor.shutdownNow();
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    public boolean isRunning() {
        return running.get();
    }

    // -------------------------------------------------------------------------
    // State exposure & streaming
    // -------------------------------------------------------------------------

    public Optional<GenerationSnapshot> latestSnapshot() {
        return Optional.ofNullable(latestSnapshot);
    }

    public List<GenerationSnapshot> history() {
        return history;
    }

    /**
     * Creates a lightweight view of the recorded generations that is cheap to serialize.
     */
    public List<GenerationSnapshot> historySummary() {
        return history.stream()
                .map(this::summarizeSnapshot)
                .collect(Collectors.toList());
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

    // -------------------------------------------------------------------------
    // Genetic algorithm orchestration
    // -------------------------------------------------------------------------

    /**
     * Core worker loop: evaluates the current population, captures a snapshot,
     * emits it to subscribers, then breeds the next generation until the stop
     * conditions are met (max generations or explicit stop).
     */
    private void runAlgorithm() {
        try {
            if (points.isEmpty() || config == null) {
                running.set(false);
                return;
            }
            int populationSize = config.populationSize();
            ExpressionNode[] population = initializePopulation(populationSize);
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

    // -------------------------------------------------------------------------
    // Population bootstrap & fitness evaluation
    // -------------------------------------------------------------------------

    private ExpressionNode[] initializePopulation(int populationSize) {
        ExpressionNode[] population = new ExpressionNode[populationSize];
        for (int i = 0; i < populationSize; i++) {
            population[i] = generateConstrainedExpression();
        }
        return population;
    }

    private void evaluatePopulation(ExpressionNode[] population, double[] fitnesses) {
        for (int i = 0; i < population.length; i++) {
            fitnesses[i] = fitness(population[i]);
        }
    }

    /**
     * Builds a serializable view of the current generation, including the curve points
     * of the best solution so the frontend can draw it immediately.
     */
    private GenerationSnapshot buildSnapshot(int generation, ExpressionNode[] population, double[] fitnesses) {
        List<PolynomialSolution> solutions = new ArrayList<>(population.length);
        for (int i = 0; i < population.length; i++) {
            solutions.add(buildSolution(population[i], fitnesses[i]));
        }
        solutions.sort(Comparator.comparingDouble(PolynomialSolution::fitness).reversed());
        PolynomialSolution bestSolution = solutions.get(0);
        List<PolynomialSolution> top = solutions.stream().limit(5).collect(Collectors.toList());
        List<Point> curve = buildCurvePoints(bestSolution.expressionTree());
        GenerationSnapshot snapshot = new GenerationSnapshot(generation, bestSolution, top, Instant.now(), curve);
        latestSnapshot = snapshot;
        return snapshot;
    }

    private PolynomialSolution buildSolution(ExpressionNode expression, double fitness) {
        int covered = 0;
        double error = 0;
        for (Point point : points) {
            double diff = evaluateExpression(expression, point.x()) - point.y();
            error += diff * diff;
            if (Math.abs(diff) <= config.alignmentTolerance()) {
                covered++;
            }
        }
        String expressionText = expressionFor(expression);
        return new PolynomialSolution(expression, fitness, covered, error, expressionText);
    }

    /**
     * Maintains an in-memory ring buffer of the last X generations for charting.
     */
    private void persistSnapshot(GenerationSnapshot snapshot) {
        history.add(snapshot);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * Broadcasts the latest snapshot to all connected SSE clients.
     */
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

    // -------------------------------------------------------------------------
    // Genetic operators (selection, crossover, mutation)
    // -------------------------------------------------------------------------

    private ExpressionNode[] nextGeneration(ExpressionNode[] population, double[] fitnesses) {
        int populationSize = population.length;
        ExpressionNode[] next = new ExpressionNode[populationSize];
        int elitismCount = Math.max(1, (int) Math.round(populationSize * (config.elitismRate() / 100.0)));
        Integer[] indices = new Integer[populationSize];
        for (int i = 0; i < populationSize; i++) {
            indices[i] = i;
        }
        Arrays.sort(indices, Comparator.comparingDouble((Integer idx) -> fitnesses[idx]).reversed());
        for (int i = 0; i < elitismCount; i++) {
            next[i] = population[indices[i]];
        }
        for (int i = elitismCount; i < populationSize; i++) {
            ExpressionNode parent1 = tournamentSelect(population, fitnesses);
            ExpressionNode parent2 = tournamentSelect(population, fitnesses);
            ExpressionNode child = crossover(parent1, parent2);
            child = mutate(child);
            next[i] = ensureConstraints(child);
        }
        return next;
    }

    /**
     * Simple tournament selection to bias towards high-fitness parents while
     * still keeping some diversity.
     */
    private ExpressionNode tournamentSelect(ExpressionNode[] population, double[] fitnesses) {
        int tournamentSize = Math.max(2, (int) Math.round(population.length * 0.05));
        ExpressionNode best = null;
        double bestFitness = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tournamentSize; i++) {
            int idx = (int) (Math.random() * population.length);
            if (fitnesses[idx] > bestFitness) {
                bestFitness = fitnesses[idx];
                best = population[idx];
            }
        }
        return best;
    }

    private ExpressionNode crossover(ExpressionNode parent1, ExpressionNode parent2) {
        double rate = config.crossoverRate() / 100.0;
        if (Math.random() > rate) {
            return parent1;
        }
        ExpressionNode donor = pickRandomSubtree(parent2);
        ExpressionNode target = pickRandomSubtree(parent1);
        return replaceSubtree(parent1, target, donor);
    }

    /**
     * Applies recursive mutations and re-validates constraints to avoid trees that
     * exceed depth or node limits.
     */
    private ExpressionNode mutate(ExpressionNode chromosome) {
        double rate = config.mutationRate() / 100.0;
        ExpressionNode mutated = mutateRecursive(chromosome, rate, 0);
        return ensureConstraints(mutated);
    }

    /**
     * Multi-objective fitness: reward number of covered points and penalize
     * squared error to encourage both alignment and precision.
     */
    private double fitness(ExpressionNode chromosome) {
        int covered = 0;
        double error = 0;
        for (Point point : points) {
            double diff = evaluateExpression(chromosome, point.x()) - point.y();
            error += diff * diff;
            if (Math.abs(diff) <= config.alignmentTolerance()) {
                covered++;
            }
        }
        return covered * 1000 - error;
    }

    private String formatCoeff(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private List<Point> buildCurvePoints(ExpressionNode expression) {
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
            double y = evaluateExpression(expression, x);
            if (!Double.isFinite(y)) {
                continue;
            }
            curve.add(new Point(x, y));
        }
        return curve;
    }

    // -------------------------------------------------------------------------
    // Expression-tree helpers
    // -------------------------------------------------------------------------

    private ExpressionNode generateConstrainedExpression() {
        ExpressionNode expr;
        int attempts = 0;
        do {
            int targetDepth = ThreadLocalRandom.current().nextInt(MIN_TREE_DEPTH, MAX_TREE_DEPTH + 1);
            expr = randomExpression(targetDepth - 1);
            attempts++;
        } while (!isWithinConstraints(expr) && attempts < 10);
        if (!isWithinConstraints(expr)) {
            expr = new VariableNode();
        }
        return expr;
    }

    private ExpressionNode ensureConstraints(ExpressionNode node) {
        if (node == null) {
            return generateConstrainedExpression();
        }
        if (isWithinConstraints(node)) {
            return node;
        }
        return generateConstrainedExpression();
    }

    private boolean isWithinConstraints(ExpressionNode node) {
        TreeStats stats = computeStats(node);
        return stats.depth() <= MAX_TREE_DEPTH && stats.nodeCount() <= MAX_NODE_COUNT;
    }

    private TreeStats computeStats(ExpressionNode node) {
        return computeStats(node, 1);
    }

    private TreeStats computeStats(ExpressionNode node, int depth) {
        int maxDepth = depth;
        int count = 1;
        if (node instanceof UnaryNode unary) {
            TreeStats child = computeStats(unary.child(), depth + 1);
            maxDepth = Math.max(maxDepth, child.depth());
            count += child.nodeCount();
        } else if (node instanceof BinaryNode binary) {
            TreeStats left = computeStats(binary.left(), depth + 1);
            TreeStats right = computeStats(binary.right(), depth + 1);
            maxDepth = Math.max(maxDepth, Math.max(left.depth(), right.depth()));
            count += left.nodeCount() + right.nodeCount();
        }
        return new TreeStats(maxDepth, count);
    }

    private ExpressionNode randomExpression(int remainingDepth) {
        if (remainingDepth <= 0) {
            return randomLeaf();
        }
        double roll = Math.random();
        if (roll < 0.4) {
            return new UnaryNode(randomUnaryOperator(), randomExpression(remainingDepth - 1));
        }
        return new BinaryNode(randomBinaryOperator(), randomExpression(remainingDepth - 1), randomExpression(remainingDepth - 1));
    }

    private ExpressionNode randomLeaf() {
        if (Math.random() < 0.5) {
            return new VariableNode();
        }
        double span = explorationSpan();
        double value = ThreadLocalRandom.current().nextDouble(-span, span);
        return new ConstantNode(clamp(value, CONSTANT_MIN, CONSTANT_MAX));
    }

    private UnaryOperator randomUnaryOperator() {
        UnaryOperator[] values = UnaryOperator.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    private BinaryOperator randomBinaryOperator() {
        BinaryOperator[] values = BinaryOperator.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    private ExpressionNode mutateRecursive(ExpressionNode node, double rate, int depth) {
        if (Math.random() < rate * 0.2) {
            int desiredDepth = Math.max(1, MAX_TREE_DEPTH - depth);
            int remainingDepth = Math.max(0, desiredDepth - 1);
            return randomExpression(remainingDepth);
        }
        if (node instanceof ConstantNode constant) {
            if (Math.random() < rate) {
                double span = explorationSpan();
                double deltaRange = Math.max(0.1, span * 0.25);
                double delta = ThreadLocalRandom.current().nextDouble(-deltaRange, deltaRange);
                double newValue = clamp(constant.value() + delta, CONSTANT_MIN, CONSTANT_MAX);
                return new ConstantNode(newValue);
            }
            return constant;
        }
        if (node instanceof VariableNode) {
            if (Math.random() < rate * 0.2) {
                return new VariableNode();
            }
            return node;
        }
        if (node instanceof UnaryNode unary) {
            ExpressionNode child = mutateRecursive(unary.child(), rate, depth + 1);
            UnaryOperator operator = Math.random() < rate * 0.5 ? randomUnaryOperator() : unary.operator();
            return new UnaryNode(operator, child);
        }
        if (node instanceof BinaryNode binary) {
            ExpressionNode left = mutateRecursive(binary.left(), rate, depth + 1);
            ExpressionNode right = mutateRecursive(binary.right(), rate, depth + 1);
            BinaryOperator operator = Math.random() < rate * 0.5 ? randomBinaryOperator() : binary.operator();
            return new BinaryNode(operator, left, right);
        }
        return node;
    }

    private ExpressionNode pickRandomSubtree(ExpressionNode root) {
        List<ExpressionNode> nodes = new ArrayList<>();
        collectSubtrees(root, nodes);
        int idx = ThreadLocalRandom.current().nextInt(nodes.size());
        return nodes.get(idx);
    }

    private void collectSubtrees(ExpressionNode node, List<ExpressionNode> buffer) {
        buffer.add(node);
        if (node instanceof UnaryNode unary) {
            collectSubtrees(unary.child(), buffer);
        } else if (node instanceof BinaryNode binary) {
            collectSubtrees(binary.left(), buffer);
            collectSubtrees(binary.right(), buffer);
        }
    }

    private ExpressionNode replaceSubtree(ExpressionNode current, ExpressionNode target, ExpressionNode replacement) {
        if (current == target) {
            return replacement;
        }
        if (current instanceof UnaryNode unary) {
            return new UnaryNode(unary.operator(), replaceSubtree(unary.child(), target, replacement));
        }
        if (current instanceof BinaryNode binary) {
            ExpressionNode left = replaceSubtree(binary.left(), target, replacement);
            ExpressionNode right = replaceSubtree(binary.right(), target, replacement);
            return new BinaryNode(binary.operator(), left, right);
        }
        return current;
    }

    /**
     * Safely evaluates the expression tree at a given x, clamping dangerous cases
     * (division by zero, log of non-positive numbers, overflows) to sane defaults.
     */
    private double evaluateExpression(ExpressionNode node, double x) {
        double result;
        if (node instanceof ConstantNode constant) {
            result = constant.value();
        } else if (node instanceof VariableNode) {
            result = x;
        } else if (node instanceof UnaryNode unary) {
            double child = evaluateExpression(unary.child(), x);
            result = switch (unary.operator()) {
                case SIN -> Math.sin(child);
                case COS -> Math.cos(child);
                case EXP -> Math.exp(Math.max(-10, Math.min(10, child)));
                case LOG -> child <= EPSILON ? 0 : Math.log(child);
            };
        } else if (node instanceof BinaryNode binary) {
            double left = evaluateExpression(binary.left(), x);
            double right = evaluateExpression(binary.right(), x);
            result = switch (binary.operator()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case DIVIDE -> Math.abs(right) <= EPSILON ? left : left / right;
            };
        } else {
            result = 0;
        }
        return Double.isFinite(result) ? result : 0;
    }

    private String expressionFor(ExpressionNode node) {
        if (node instanceof ConstantNode constant) {
            return formatCoeff(constant.value());
        }
        if (node instanceof VariableNode) {
            return "x";
        }
        if (node instanceof UnaryNode unary) {
            return unary.operator().symbol() + "(" + expressionFor(unary.child()) + ")";
        }
        if (node instanceof BinaryNode binary) {
            return "(" + expressionFor(binary.left()) + " " + binary.operator().symbol() + " " + expressionFor(binary.right()) + ")";
        }
        return "?";
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double explorationSpan() {
        if (config == null) {
            return CONSTANT_MAX;
        }
        double span = Math.max(1d, config.explorationRange());
        return clamp(span, 1d, CONSTANT_MAX);
    }

    /**
     * Removes heavy payloads (curve points, expression tree) for history responses.
     */
    private GenerationSnapshot summarizeSnapshot(GenerationSnapshot snapshot) {
        PolynomialSolution best = summarizeSolution(snapshot.bestSolution());
        return new GenerationSnapshot(
                snapshot.generation(),
                best,
                List.of(),
                snapshot.timestamp(),
                List.of()
        );
    }

    private PolynomialSolution summarizeSolution(PolynomialSolution solution) {
        if (solution == null) {
            return null;
        }
        return new PolynomialSolution(
                null,
                solution.fitness(),
                solution.pointsCovered(),
                solution.totalError(),
                solution.expression()
        );
    }

    private record TreeStats(int depth, int nodeCount) { }
}
