package com.example.genetic.model.expression;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("unary")
public record UnaryNode(UnaryOperator operator, ExpressionNode child) implements ExpressionNode {
}
