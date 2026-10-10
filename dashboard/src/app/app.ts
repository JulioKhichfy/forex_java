import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, SlicePipe],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit, OnDestroy {
  private api = inject(Api);
  protected status = signal<Record<string, any> | null>(null);
  protected offline = signal(false);
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 30_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private refresh(): void {
    this.api.status().subscribe({
      next: (s) => { this.status.set(s); this.offline.set(false); },
      error: () => this.offline.set(true),
    });
  }
}
