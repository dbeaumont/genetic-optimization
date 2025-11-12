package com.example.genetic.model.expression;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "nodeType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConstantNode.class, name = "constant"),
        @JsonSubTypes.Type(value = VariableNode.class, name = "variable"),
        @JsonSubTypes.Type(value = UnaryNode.class, name = "unary"),
        @JsonSubTypes.Type(value = BinaryNode.class, name = "binary")
})
public sealed interface ExpressionNode permits ConstantNode, VariableNode, UnaryNode, BinaryNode {
}
