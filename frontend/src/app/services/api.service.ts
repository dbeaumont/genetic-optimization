import { Injectable, NgZone } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AlgorithmStateResponse, GeneticConfig, GenerationSnapshot, Point } from '../models/domain.models';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly baseUrl = environment.apiUrl;

  constructor(private http: HttpClient, private zone: NgZone) {}

  getPoints(): Observable<Point[]> {
    return this.http.get<Point[]>(`${this.baseUrl}/points`);
  }

  savePoints(points: Point[]): Observable<Point[]> {
    return this.http.put<Point[]>(`${this.baseUrl}/points`, points);
  }

  getDefaultConfig(): Observable<GeneticConfig> {
    return this.http.get<GeneticConfig>(`${this.baseUrl}/algorithm/default-config`);
  }

  getState(): Observable<AlgorithmStateResponse> {
    return this.http.get<AlgorithmStateResponse>(`${this.baseUrl}/algorithm/state`);
  }

  startRun(config: GeneticConfig, points: Point[]): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/algorithm/start`, { config, points });
  }

  stopRun(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/algorithm/stop`, {});
  }

  resetRun(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/algorithm/reset`, {});
  }

  streamGenerations(onMessage: (snapshot: GenerationSnapshot) => void): EventSource {
    const source = new EventSource(`${this.baseUrl}/algorithm/stream`);
    source.onmessage = event => {
      this.zone.run(() => {
        const data = JSON.parse(event.data) as GenerationSnapshot;
        onMessage(data);
      });
    };
    return source;
  }
}
