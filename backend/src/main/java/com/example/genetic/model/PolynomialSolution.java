package com.example.genetic.model;

import com.example.genetic.model.expression.ExpressionNode;

public record PolynomialSolution(ExpressionNode expressionTree,
                                 double fitness,
                                 int pointsCovered,
                                 double totalError,
                                 String expression) {

    public String equation() {
        return expression;
    }

    @Override
    public String toString() {
        return "PolynomialSolution{" +
                "expressionTree=" + expressionTree +
                ", fitness=" + fitness +
                ", pointsCovered=" + pointsCovered +
                ", totalError=" + totalError +
                ", expression='" + expression + '\'' +
                '}';
    }
}
