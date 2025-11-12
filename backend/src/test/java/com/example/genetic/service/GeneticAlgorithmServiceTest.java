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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
        assertThat(snapshot.bestSolution().expressionTree()).isNotNull();
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

    @Test
    void bestSolutionTreeShouldRespectConstraints() {
        GeneticConfig config = new GeneticConfig(
                25,
                30,
                1,
                12,
                65,
                5,
                0.4,
                5
        );

        service.start(new RunRequest(config, samplePoints));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !service.isRunning());

        PolynomialSolution best = service.latestSnapshot().orElseThrow().bestSolution();
        TreeMetrics metrics = computeMetrics(best.expressionTree());
        assertThat(metrics.depth()).isLessThanOrEqualTo(5);
        assertThat(metrics.count()).isLessThanOrEqualTo(50);
        assertConstantsWithinBounds(best.expressionTree());
    }

    @Test
    void evaluateExpressionShouldGuardUnsafeOperations() throws Exception {
        ExpressionNode logNode = new UnaryNode(UnaryOperator.LOG, new ConstantNode(-2));
        double logValue = invokeEvaluate(logNode, 0);
        assertThat(logValue).isEqualTo(0d);

        ExpressionNode division = new BinaryNode(
                BinaryOperator.DIVIDE,
                new ConstantNode(6),
                new ConstantNode(0)
        );
        double divisionValue = invokeEvaluate(division, 0);
        assertThat(divisionValue).isEqualTo(6d);
    }

    private double invokeEvaluate(ExpressionNode node, double x) throws Exception {
        Method method = GeneticAlgorithmService.class
                .getDeclaredMethod("evaluateExpression", ExpressionNode.class, double.class);
        method.setAccessible(true);
        return (double) method.invoke(service, node, x);
    }

    private TreeMetrics computeMetrics(ExpressionNode node) {
        return computeMetrics(node, 1);
    }

    private TreeMetrics computeMetrics(ExpressionNode node, int depth) {
        int maxDepth = depth;
        int count = 1;
        if (node instanceof UnaryNode unary) {
            TreeMetrics child = computeMetrics(unary.child(), depth + 1);
            maxDepth = Math.max(maxDepth, child.depth());
            count += child.count();
        } else if (node instanceof BinaryNode binary) {
            TreeMetrics left = computeMetrics(binary.left(), depth + 1);
            TreeMetrics right = computeMetrics(binary.right(), depth + 1);
            maxDepth = Math.max(maxDepth, Math.max(left.depth(), right.depth()));
            count += left.count() + right.count();
        }
        return new TreeMetrics(maxDepth, count);
    }

    private void assertConstantsWithinBounds(ExpressionNode node) {
        if (node instanceof ConstantNode constant) {
            assertThat(constant.value()).isBetween(-10d, 10d);
            return;
        }
        if (node instanceof UnaryNode unary) {
            assertConstantsWithinBounds(unary.child());
        } else if (node instanceof BinaryNode binary) {
            assertConstantsWithinBounds(binary.left());
            assertConstantsWithinBounds(binary.right());
        }
    }

    private record TreeMetrics(int depth, int count) { }
}
