export interface Point {
  x: number;
  y: number;
}

export interface GeneticConfig {
  populationSize: number;
  maxGenerations: number;
  iterationDelayMs: number;
  mutationRate: number;
  crossoverRate: number;
  elitismRate: number;
  alignmentTolerance: number;
  explorationRange: number;
}

export type ExpressionNode =
  | { nodeType: 'constant'; value: number }
  | { nodeType: 'variable' }
  | { nodeType: 'unary'; operator: UnaryOperator; child: ExpressionNode }
  | { nodeType: 'binary'; operator: BinaryOperator; left: ExpressionNode; right: ExpressionNode };

export type UnaryOperator = 'SIN' | 'COS' | 'EXP' | 'LOG';
export type BinaryOperator = 'ADD' | 'SUBTRACT' | 'MULTIPLY' | 'DIVIDE';

export interface PolynomialSolution {
  expressionTree: ExpressionNode;
  fitness: number;
  pointsCovered: number;
  totalError: number;
  expression: string;
}

export interface GenerationSnapshot {
  generation: number;
  bestSolution: PolynomialSolution;
  topSolutions: PolynomialSolution[];
  timestamp: string;
  curvePoints: Point[];
}

export interface AlgorithmStateResponse {
  running: boolean;
  config: GeneticConfig;
  latestGeneration: GenerationSnapshot | null;
  history: GenerationSnapshot[];
}
