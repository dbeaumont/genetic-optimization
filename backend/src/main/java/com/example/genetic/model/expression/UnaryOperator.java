package com.example.genetic.model.expression;

public enum UnaryOperator {
    SIN("sin"),
    COS("cos"),
    EXP("exp"),
    LOG("log");

    private final String symbol;

    UnaryOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
