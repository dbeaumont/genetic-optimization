package com.example.polyfit.model;

import java.util.Arrays;

public record PolynomialSolution(double[] coefficients,
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
                "coefficients=" + Arrays.toString(coefficients) +
                ", fitness=" + fitness +
                ", pointsCovered=" + pointsCovered +
                ", totalError=" + totalError +
                ", expression='" + expression + '\'' +
                '}';
    }
}
