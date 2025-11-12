package com.example.genetic.model.expression;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("binary")
public record BinaryNode(BinaryOperator operator, ExpressionNode left, ExpressionNode right) implements ExpressionNode {
}
