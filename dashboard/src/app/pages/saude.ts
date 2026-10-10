import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { JsonPipe, KeyValuePipe } from '@angular/common';
import { Api } from '../api';

/** Saúde do sistema: o que o /api/status devolve (MT5, coletores, Jev, risco), sem esconder nada. */
@Component({
  selector: 'page-saude',
  imports: [JsonPipe, KeyValuePipe],
  template: `
    <h1>Saúde</h1>
    @if (error()) {
      <div class="notice">A API não respondeu. O servidor (java -jar jev-app.jar) está rodando?</div>
    }
    @if (status(); as s) {
      <div class="grid two">
        @for (e of s | keyvalue: keep; track e.key) {
          <section class="panel">
            <h3>{{ e.key }}</h3>
            @if (isObject(e.value)) {
              <pre class="mono small">{{ e.value | json }}</pre>
            } @else {
              <div class="mono">{{ e.value }}</div>
            }
          </section>
        }
      </div>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `pre { margin: 0; white-space: pre-wrap; word-break: break-word; max-height: 360px; overflow: auto; }`,
})
export class Saude implements OnInit, OnDestroy {
  private api = inject(Api);
  protected status = signal<Record<string, unknown> | null>(null);
  protected error = signal(false);
  private timer?: ReturnType<typeof setInterval>;
  protected keep = () => 0;   // mantém a ordem da API

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 15_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected isObject(v: unknown): boolean {
    return v !== null && typeof v === 'object';
  }

  private load(): void {
    this.api.status().subscribe({
      next: (s) => { this.status.set(s); this.error.set(false); },
      error: () => this.error.set(true),
    });
  }
}
