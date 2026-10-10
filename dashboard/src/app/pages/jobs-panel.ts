import { Component, ElementRef, OnDestroy, OnInit, inject, input, output, signal, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Api, JobDef, JobStatus } from '../api';

/** Botões de tarefas (mesmos comandos da CLI, num processo separado) e o log da execução atual. */
@Component({
  selector: 'jobs-panel',
  imports: [DatePipe],
  template: `
    <div class="buttons">
      @for (j of defs(); track j.name) {
        <button [class.primary]="j.name === highlight()" [disabled]="running()" (click)="start(j)" [title]="j.help">
          {{ j.label }}</button>
      }
    </div>
    @if (error()) { <p class="down small">{{ error() }}</p> }
    @if (status(); as s) {
      <p class="small">
        <b>{{ s.label }}</b> ·
        @if (s.running) { <span class="badge warn">rodando: {{ s.step }}</span> }
        @else if (s.exitCode === 0) { <span class="badge ok">concluída</span> }
        @else { <span class="badge high">falhou (código {{ s.exitCode }})</span> }
        <span class="muted"> · início {{ s.startedAt | date: 'dd/MM HH:mm:ss' }}
          @if (s.finishedAt) { · fim {{ s.finishedAt | date: 'HH:mm:ss' }} }</span>
      </p>
      <pre #log class="log mono small">{{ s.tail.join('\\n') }}</pre>
    }
  `,
  styles: `
    .buttons { display: flex; gap: 8px; flex-wrap: wrap; }
    .log { max-height: 280px; overflow: auto; background: var(--panel-2); padding: 8px; border-radius: 6px;
      white-space: pre-wrap; word-break: break-word; margin: 0; }
  `,
})
export class JobsPanel implements OnInit, OnDestroy {
  private api = inject(Api);
  names = input<string[]>([]);
  highlight = input<string>('');
  finished = output<JobStatus>();
  protected defs = signal<JobDef[]>([]);
  protected status = signal<JobStatus | null>(null);
  protected running = signal(false);
  protected error = signal<string | null>(null);
  private log = viewChild<ElementRef<HTMLPreElement>>('log');
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 3_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected start(j: JobDef): void {
    if (!confirm(`${j.label}?\n\n${j.help}`)) return;
    this.error.set(null);
    this.api.startJob(j.name).subscribe({
      next: (s) => { this.status.set(s); this.running.set(true); },
      error: (e) => this.error.set(e?.error?.message ?? 'Não consegui iniciar'),
    });
  }

  private load(): void {
    this.api.jobs().subscribe({
      next: (r) => {
        const wanted = this.names();
        this.defs.set(r.available.filter((d) => wanted.length === 0 || wanted.includes(d.name)));
        const was = this.running();
        const s = r.current;
        this.status.set(s);
        this.running.set(!!s?.running);
        if (was && s && !s.running) this.finished.emit(s);
        const el = this.log()?.nativeElement;
        if (el) setTimeout(() => (el.scrollTop = el.scrollHeight));
      },
      error: () => {},
    });
  }
}
