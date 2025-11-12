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

export interface PolynomialSolution {
  coefficients: number[];
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
