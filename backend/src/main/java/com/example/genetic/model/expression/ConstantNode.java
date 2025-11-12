package com.example.genetic.model.expression;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("constant")
public record ConstantNode(double value) implements ExpressionNode {
}
