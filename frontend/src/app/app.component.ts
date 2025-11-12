import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ApiService } from './services/api.service';
import { AlgorithmStateResponse, ExpressionNode, GeneticConfig, GenerationSnapshot, Point, PolynomialSolution } from './models/domain.models';
import { ChartConfiguration } from 'chart.js';
import { finalize, forkJoin } from 'rxjs';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('plotCanvas') plotCanvas?: ElementRef<HTMLCanvasElement>;

  title = 'Genetic Evolution';
  private plotBounds = { minX: -5, maxX: 5, minY: -5, maxY: 5 };
  configForm!: FormGroup;
  points: Point[] = [];
  running = false;
  latestSnapshot: GenerationSnapshot | null = null;
  history: GenerationSnapshot[] = [];
  saving = false;
  private readonly unarySymbols: Record<string, string> = { SIN: 'sin', COS: 'cos', EXP: 'exp', LOG: 'log' };
  private readonly binarySymbols: Record<string, string> = { ADD: '+', SUBTRACT: '-', MULTIPLY: '*', DIVIDE: '/' };
  private source?: EventSource;

  fitnessChartConfig: ChartConfiguration<'line'>['data'] = {
    labels: [],
    datasets: [
      {
        data: [],
        label: 'Fitness optimale',
        borderColor: '#1976d2',
        backgroundColor: 'rgba(25,118,210,0.3)',
        fill: true,
        tension: 0.3,
        pointRadius: 1
      }
    ]
  };

  fitnessChartOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    scales: {
      x: { title: { display: true, text: 'Génération' } },
      y: { title: { display: true, text: 'Fitness' } }
    }
  };

  constructor(private fb: FormBuilder, private api: ApiService) {}

  ngOnInit(): void {
    this.configForm = this.fb.group({
      populationSize: [150, [Validators.required, Validators.min(10)]],
      maxGenerations: [2000, [Validators.required, Validators.min(1)]],
      iterationDelayMs: [100, [Validators.required, Validators.min(1)]],
      mutationRate: [20, [Validators.required, Validators.min(0), Validators.max(100)]],
      crossoverRate: [70, [Validators.required, Validators.min(0), Validators.max(100)]],
      elitismRate: [10, [Validators.required, Validators.min(0), Validators.max(100)]],
      alignmentTolerance: [0.2, [Validators.required, Validators.min(0)]],
      explorationRange: [10, [Validators.required, Validators.min(0.1)]]
    });

    forkJoin([this.api.getPoints(), this.api.getDefaultConfig(), this.api.getState()])
      .subscribe(([points, config, state]) => {
        this.points = points;
        this.configForm.patchValue(config);
        this.consumeState(state);
      });
  }

  ngAfterViewInit(): void {
    setTimeout(() => this.renderPlot(), 300);
  }

  ngOnDestroy(): void {
    this.source?.close();
  }

  addPoint(): void {
    this.points = [...this.points, { x: 0, y: 0 }];
    this.renderPlot();
  }

  removePoint(index: number): void {
    this.points = this.points.filter((_, i) => i !== index);
    this.renderPlot();
  }

  savePoints(): void {
    this.saving = true;
    this.api.savePoints(this.points)
      .pipe(finalize(() => this.saving = false))
      .subscribe(updated => {
        this.points = updated;
        this.renderPlot();
      });
  }

  randomizePoints(): void {
    const coeffs = Array.from({ length: 4 }, () => (Math.random() * 2 - 1));
    const rangeX = this.plotBounds.maxX - this.plotBounds.minX;
    const count = 20;
    this.points = Array.from({ length: count }, () => {
      const x = this.plotBounds.minX + Math.random() * rangeX;
      let y = 0;
      coeffs.forEach((c, power) => (y += c * Math.pow(x, power)));
      y += (Math.random() * 2 - 1);
      return { x: parseFloat(x.toFixed(3)), y: parseFloat(y.toFixed(3)) };
    });
    this.renderPlot();
  }

  start(): void {
    if (this.configForm.invalid) {
      this.configForm.markAllAsTouched();
      return;
    }
    const config = this.configForm.value as GeneticConfig;
    this.saving = true;
    this.api.savePoints(this.points)
      .pipe(finalize(() => this.saving = false))
      .subscribe(() => {
        this.api.startRun(config, this.points).subscribe(() => {
          this.running = true;
          this.openStream();
        });
      });
  }

  stop(): void {
    this.api.stopRun().subscribe(() => {
      this.running = false;
      this.source?.close();
    });
  }

  reset(): void {
    this.saving = true;
    this.api.resetRun()
      .pipe(finalize(() => this.saving = false))
      .subscribe(() => {
        this.latestSnapshot = null;
        this.history = [];
        this.running = false;
        this.source?.close();
        this.renderPlot();
        this.updateChart();
      });
  }

  private openStream(): void {
    this.source?.close();
    this.source = this.api.streamGenerations(snapshot => {
      this.latestSnapshot = snapshot;
      this.history = [...this.history.filter(h => h.generation !== snapshot.generation), snapshot];
      this.updateChart();
      this.renderPlot();
      this.running = true;
    });
  }

  private consumeState(state: AlgorithmStateResponse): void {
    this.running = state.running;
    this.history = state.history ?? [];
    this.latestSnapshot = state.latestGeneration ?? null;
    this.updateChart();
    this.renderPlot();
    if (state.running) {
      this.openStream();
    }
  }

  private updateChart(): void {
    const sorted = [...this.history].sort((a, b) => a.generation - b.generation);
    const labels = sorted.map(s => s.generation);
    const data = sorted.map(s => s.bestSolution.fitness);
    this.fitnessChartConfig = {
      labels,
      datasets: [
        {
          ...this.fitnessChartConfig.datasets[0],
          data
        }
      ]
    };
  }

  private renderPlot(): void {
    const canvas = this.plotCanvas?.nativeElement;
    if (!canvas) {
      return;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }
    const width = canvas.width;
    const height = canvas.height;
    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#0d1117';
    ctx.fillRect(0, 0, width, height);
    ctx.strokeStyle = '#2b2f36';
    ctx.lineWidth = 1;

    const curvePoints = this.latestSnapshot?.curvePoints ?? [];
    const xs = [...this.points.map(p => p.x), ...curvePoints.map(p => p.x)];
    const ys = [...this.points.map(p => p.y), ...curvePoints.map(p => p.y)];
    const minX = Math.min(...xs, -5);
    const maxX = Math.max(...xs, 5);
    const minY = Math.min(...ys, -5);
    const maxY = Math.max(...ys, 5);
    this.plotBounds = { minX, maxX, minY, maxY };

    const scaleX = width / (maxX - minX || 1);
    const scaleY = height / (maxY - minY || 1);

    const toCanvasX = (x: number) => (x - minX) * scaleX;
    const toCanvasY = (y: number) => height - (y - minY) * scaleY;

    // draw axes
    ctx.strokeStyle = '#555';
    ctx.beginPath();
    ctx.moveTo(0, toCanvasY(0));
    ctx.lineTo(width, toCanvasY(0));
    ctx.moveTo(toCanvasX(0), 0);
    ctx.lineTo(toCanvasX(0), height);
    ctx.stroke();

    // draw points
    ctx.fillStyle = '#ffb74d';
    this.points.forEach(point => {
      ctx.beginPath();
      ctx.arc(toCanvasX(point.x), toCanvasY(point.y), 4, 0, Math.PI * 2);
      ctx.fill();
    });

    // draw polynomial
    if (curvePoints.length > 0) {
      ctx.strokeStyle = '#64b5f6';
      ctx.lineWidth = 2;
      ctx.beginPath();
      curvePoints.forEach((point, index) => {
        const cx = toCanvasX(point.x);
        const cy = toCanvasY(point.y);
        if (index === 0) {
          ctx.moveTo(cx, cy);
        } else {
          ctx.lineTo(cx, cy);
        }
      });
      ctx.stroke();
    }
  }

  exportPlot(): void {
    const canvas = this.plotCanvas?.nativeElement;
    if (!canvas) {
      return;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return;
    }
    this.renderPlot();
    if (this.latestSnapshot) {
      this.drawExportOverlay(ctx, canvas);
    }
    const link = document.createElement('a');
    link.href = canvas.toDataURL('image/png');
    link.download = `polyfit-${Date.now()}.png`;
    link.click();
    this.renderPlot();
  }

  trackByIndex(index: number): number {
    return index;
  }

  formatExpression(solution?: PolynomialSolution | null): string {
    if (!solution) {
      return '—';
    }
    return solution.expression;
  }

  private drawExportOverlay(ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement): void {
    const { width, height } = canvas;
    const overlayWidth = Math.min(320, width * 0.4);
    const overlayHeight = 70;
    const padding = 16;
    const x = width - overlayWidth - padding;
    const y = height - overlayHeight - padding;

    ctx.fillStyle = 'rgba(9, 12, 17, 0.85)';
    ctx.fillRect(x, y, overlayWidth, overlayHeight);
    ctx.strokeStyle = '#30363d';
    ctx.lineWidth = 1;
    ctx.strokeRect(x, y, overlayWidth, overlayHeight);

    ctx.fillStyle = '#e6edf3';
    ctx.font = '12px "Inter", "Segoe UI", sans-serif';
    ctx.textBaseline = 'top';

    const best = this.latestSnapshot!.bestSolution;
    const text = `f(x) = ${this.formatExpression(best)}`;
    ctx.fillText(text, x + 10, y + 10);
    ctx.fillText(`Fitness: ${best.fitness.toFixed(2)}`, x + 10, y + 28);
  }

  handleCanvasClick(event: MouseEvent): void {
    const canvas = this.plotCanvas?.nativeElement;
    if (!canvas) {
      return;
    }
    const rect = canvas.getBoundingClientRect();
    const relativeX = event.clientX - rect.left;
    const relativeY = event.clientY - rect.top;
    const worldX =
      this.plotBounds.minX +
      (relativeX / canvas.clientWidth) * (this.plotBounds.maxX - this.plotBounds.minX);
    const worldY =
      this.plotBounds.maxY -
      (relativeY / canvas.clientHeight) * (this.plotBounds.maxY - this.plotBounds.minY);
    this.points = [
      ...this.points,
      { x: parseFloat(worldX.toFixed(3)), y: parseFloat(worldY.toFixed(3)) }
    ];
    this.renderPlot();
  }

  describeNode(node: ExpressionNode): string {
    switch (node.nodeType) {
      case 'constant':
        return node.value.toFixed(2);
      case 'variable':
        return 'x';
      case 'unary':
        return this.unarySymbols[node.operator] ?? node.operator.toLowerCase();
      case 'binary':
        return this.binarySymbols[node.operator] ?? node.operator.toLowerCase();
      default:
        return '?';
    }
  }

  childNodes(node: ExpressionNode): ExpressionNode[] {
    if (node.nodeType === 'unary') {
      return [node.child];
    }
    if (node.nodeType === 'binary') {
      return [node.left, node.right];
    }
    return [];
  }

  buildAsciiTree(node: ExpressionNode): string {
    const lines: string[] = [];
    const traverse = (current: ExpressionNode, prefix: string, isTail: boolean) => {
      const connector = prefix ? `${prefix}${isTail ? '└─ ' : '├─ '}` : '';
      lines.push(`${connector}${this.describeNode(current)}`);
      const children = this.childNodes(current);
      if (children.length === 0) {
        return;
      }
      const nextPrefix = prefix + (isTail ? '   ' : '│  ');
      children.forEach((child, index) => traverse(child, nextPrefix, index === children.length - 1));
    };
    traverse(node, '', true);
    return lines.join('\n');
  }
}
