package com.example.genetic.model.expression;

import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("variable")
public record VariableNode() implements ExpressionNode {
}
