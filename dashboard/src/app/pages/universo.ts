import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Api, MODEL_NAMES } from '../api';
import { MARKETS, MarketState } from '../market';
import { Tip } from './tip';

type Mode = 'past' | 'present' | 'future';

interface Body {
  key: string; x: number; y: number; r: number; color: string; ring: string | null; dash: boolean;
  orbit: number; dur: number; angle: number; title: string; kind: 'asteroid' | 'moon' | 'comet' | 'star';
  label?: string; url?: string; tailX?: number; tailY?: number;
}

const C = 400;                                  // centro do SVG (viewBox 800 × 800)
const GREEN = '#34d399', RED = '#f87171', GRAY = '#9ca3af', GOLD = '#fbbf24';
const SIZE: Record<string, number> = { HIGH: 15, MODERATE: 9, LOW: 5, NONE: 4 };

/** Ângulo estável para um corpo (o mesmo evento fica no mesmo lugar entre as atualizações). */
function angleOf(key: string): number {
  let h = 2166136261;
  for (let i = 0; i < key.length; i++) h = Math.imul(h ^ key.charCodeAt(i), 16777619);
  return ((h >>> 0) % 3600) / 10;
}

function brt(iso: string): string {
  const d = new Date(iso);
  return `${d.toISOString().slice(11, 16)} UTC (${d.toLocaleTimeString('pt-BR', { timeZone: 'America/Sao_Paulo', hour: '2-digit', minute: '2-digit' })} Brasília)`;
}

@Component({
  selector: 'page-universo',
  imports: [DecimalPipe, FormsModule, Tip],
  template: `
    <div class="head">
      <h1>Universo · {{ symbol() }}</h1>
      <select [ngModel]="symbol()" (ngModelChange)="pick($event)">
        @for (g of symbolGroups(); track g.market) {
          <optgroup [label]="g.label">@for (s of g.symbols; track s) { <option [value]="s">{{ s }}</option> }</optgroup>
        }
      </select>
      <div class="tabs">
        <button [class.on]="mode() === 'past'" (click)="setMode('past')">Passado (48 h)</button>
        <button [class.on]="mode() === 'present'" (click)="setMode('present')">Presente (próxima hora)</button>
        <button [class.on]="mode() === 'future'" (click)="setMode('future')">Futuro (até 7 dias)</button>
      </div>
      @if (mode() !== 'present') {
        <label class="small"><input type="checkbox" [ngModel]="showLow()" (ngModelChange)="showLow.set($event)" /> importância baixa</label>
      }
    </div>

    <div class="layout">
      <div class="space">
        <svg viewBox="0 0 800 800" role="img" [attr.aria-label]="'Universo de ' + symbol()">
          <defs>
            <radialGradient id="bg" cx="50%" cy="50%" r="70%"><stop offset="0%" stop-color="#101a33" /><stop offset="100%" stop-color="#03050c" /></radialGradient>
            <radialGradient id="planet" cx="35%" cy="35%" r="70%">
              <stop offset="0%" [attr.stop-color]="planetLight()" /><stop offset="100%" [attr.stop-color]="planetDark()" /></radialGradient>
          </defs>
          <rect width="800" height="800" fill="url(#bg)" />
          @for (s of starfield; track $index) { <circle [attr.cx]="s[0]" [attr.cy]="s[1]" [attr.r]="s[2]" fill="#cbd5e1" [attr.opacity]="s[3]" /> }

          <!-- órbitas do tempo -->
          @for (o of rings(); track o.r) {
            <circle [attr.cx]="C" [attr.cy]="C" [attr.r]="o.r" fill="none" stroke="#334155" stroke-dasharray="3 6" />
            <text [attr.x]="C + 4" [attr.y]="C - o.r - 4" class="ring">{{ o.label }}</text>
          }

          <!-- estrelas: alertas do radar -->
          @for (b of stars(); track b.key) {
            <g class="star"><circle [attr.cx]="b.x" [attr.cy]="b.y" [attr.r]="b.r" [attr.fill]="b.color"><title>{{ b.title }}</title></circle></g>
          }

          <!-- cometas: notícias -->
          @for (b of comets(); track b.key) {
            <g class="comet" (click)="open(b.url)">
              <line [attr.x1]="b.x" [attr.y1]="b.y" [attr.x2]="b.tailX" [attr.y2]="b.tailY" stroke="#93c5fd" stroke-width="2" stroke-linecap="round" opacity="0.5" />
              <circle [attr.cx]="b.x" [attr.cy]="b.y" r="4" fill="#bfdbfe"><title>{{ b.title }}</title></circle>
            </g>
          }

          <!-- asteroides: divulgações -->
          @for (b of asteroids(); track b.key) {
            <g>
              <animateTransform attributeName="transform" type="rotate" [attr.from]="'0 ' + C + ' ' + C" [attr.to]="'360 ' + C + ' ' + C" [attr.dur]="b.dur + 's'" repeatCount="indefinite" />
              <g class="asteroid" [class.sel]="selected() === b.key" (click)="selected.set(b.key)">
                @if (b.ring) { <circle [attr.cx]="b.x" [attr.cy]="b.y" [attr.r]="b.r + 4" fill="none" [attr.stroke]="b.ring" stroke-width="2" [attr.stroke-dasharray]="b.dash ? '3 3' : null" /> }
                <circle [attr.cx]="b.x" [attr.cy]="b.y" [attr.r]="b.r" [attr.fill]="b.color" stroke="#0b1020" stroke-width="1.5"><title>{{ b.title }}</title></circle>
              </g>
            </g>
          }

          <!-- o planeta -->
          <circle [attr.cx]="C" [attr.cy]="C" r="58" fill="url(#planet)" />
          <circle [attr.cx]="C" [attr.cy]="C" r="66" fill="none" [attr.stroke]="planetDark()" stroke-opacity="0.35" stroke-width="6" />
          <text [attr.x]="C" [attr.y]="C - 6" class="pname">{{ symbol() }}</text>
          <text [attr.x]="C" [attr.y]="C + 14" class="pprice">{{ priceText() }}</text>
          <text [attr.x]="C" [attr.y]="C + 30" class="pmove">{{ moveText() }}</text>

          <!-- luas: previsões (paradas, 25/10/2026: o operador lê o detalhe passando o mouse) -->
          @for (b of moons(); track b.key) {
            <g class="moon">
              <circle [attr.cx]="b.x" [attr.cy]="b.y" [attr.r]="b.r" [attr.fill]="b.color" stroke="#0b1020"><title>{{ b.title }}</title></circle>
              <text [attr.x]="b.x" [attr.y]="b.y + 3" class="mlabel">{{ b.label }}</text>
            </g>
          }
        </svg>
        @if (error()) { <p class="down small">{{ error() }}</p> }
      </div>

      <aside class="panel side">
        <h3>Legenda <tip text="Planeta = o símbolo (cor: movimento das últimas 24 h). Luas = previsões dos modelos (A = Preço, E = Eventos, B = Eventos + Preço; 15/60 min). Asteroides = divulgações do calendário das moedas do símbolo: mais perto do planeta = mais perto no tempo; maior = mais importante. Cometas = notícias dos bancos centrais (48 h; clique para abrir). Estrelas coloridas = alertas do radar." /></h3>
        <ul class="legend small">
          <li><span class="dot" [style.background]="GREEN"></span> {{ mode() === 'past' ? 'subiu depois (15 min)' : 'histórico: tende a valorizar o símbolo' }}</li>
          <li><span class="dot" [style.background]="RED"></span> {{ mode() === 'past' ? 'caiu depois (15 min)' : 'histórico: tende a desvalorizar' }}</li>
          <li><span class="dot" [style.background]="GRAY"></span> {{ mode() === 'past' ? 'lateral ou sem previsão de evento' : 'sem histórico ou sem padrão' }}</li>
          @if (mode() === 'past') { <li><span class="ringdot solid"></span> o modelo acertou · <span class="ringdot dashed"></span> errou</li> }
        </ul>
        @if (mode() !== 'past') {
          <p class="small muted">A cor de um evento que ainda não saiu NÃO é previsão do número: é o lado que o consenso espera
            (previsto × anterior) combinado com o que esse evento fez com {{ symbol() }} no passado (mapa de eventos).</p>
        }

        <h3>{{ mode() === 'past' ? 'O que aconteceu' : 'Próximos' }} ({{ list().length }})</h3>
        <div class="list">
          @for (e of list(); track e.key) {
            <div class="item" [class.sel]="selected() === e.key" (click)="selected.set(e.key)">
              <div><span class="dot" [style.background]="e.color"></span> <b>{{ e.name }}</b> <span class="badge">{{ e.currency }}</span>
                @if (e.importance === 'HIGH') { <span class="badge high">alta</span> }</div>
              <div class="small muted">{{ e.when }}</div>
              @if (selected() === e.key) { <div class="small detail">{{ e.detail }}</div> }
            </div>
          } @empty { <p class="small muted">Nenhuma divulgação das moedas de {{ symbol() }} neste período.</p> }
        </div>
      </aside>
    </div>
  `,
  styles: `
    .head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .head select { font: inherit; padding: 4px 8px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .tabs { display: flex; gap: 6px; }
    .tabs button.on { background: var(--accent); border-color: var(--accent); color: #fff; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px; margin-top: 12px; }
    @media (max-width: 900px) { .layout { grid-template-columns: 1fr; } }
    .space svg { width: 100%; height: auto; border-radius: 16px; display: block; }
    .ring { fill: #64748b; font-size: 11px; }
    .pname { fill: #f8fafc; font-size: 18px; font-weight: 700; text-anchor: middle; }
    .pprice { fill: #e2e8f0; font-size: 13px; text-anchor: middle; font-family: monospace; }
    .pmove { fill: #cbd5e1; font-size: 11px; text-anchor: middle; }
    .mlabel { fill: #0b1020; font-size: 8px; font-weight: 700; text-anchor: middle; pointer-events: none; }
    .asteroid, .comet { cursor: pointer; }
    .asteroid.sel circle { stroke: #fff; stroke-width: 3; }
    .star circle { animation: twinkle 2.4s ease-in-out infinite; }
    @keyframes twinkle { 0%, 100% { opacity: 0.35; } 50% { opacity: 1; } }
    .side { max-height: 820px; overflow-y: auto; }
    .legend { list-style: none; padding: 0; margin: 4px 0 8px; }
    .legend li { display: flex; align-items: center; gap: 6px; margin: 3px 0; }
    .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; }
    .ringdot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; border: 2px solid #fff; }
    .ringdot.dashed { border-style: dashed; }
    .item { padding: 6px 4px; border-bottom: 1px solid var(--border); cursor: pointer; }
    .item.sel { background: var(--panel-2); }
    .detail { margin-top: 4px; white-space: pre-line; }
  `,
})
export class Universo implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private api = inject(Api);
  private marketState = inject(MarketState);
  protected C = C;
  protected GREEN = GREEN;
  protected RED = RED;
  protected GRAY = GRAY;
  protected symbol = signal('XAUUSD');
  protected mode = signal<Mode>('present');
  protected showLow = signal(false);
  protected data = signal<any>(null);
  protected error = signal<string | null>(null);
  protected selected = signal<string | null>(null);
  protected symbolGroups = signal<{ market: string; label: string; symbols: string[] }[]>([]);
  private timer?: ReturnType<typeof setInterval>;
  /** Céu de fundo: fixo (não muda a cada atualização). */
  protected starfield: number[][] = Array.from({ length: 140 }, (_, i) => {
    const a = angleOf('s' + i), b = angleOf('t' + i);
    return [(a / 360) * 800, (b / 360) * 800, (i % 7 === 0 ? 1.4 : 0.7), 0.25 + (i % 5) / 10];
  });

  ngOnInit(): void {
    try { const s = localStorage.getItem('jev.universe.symbol'); if (s) this.symbol.set(s); } catch { /* sem armazenamento */ }
    this.api.tradeState().subscribe({
      next: (st) => {
        const by = st.symbols_by_market ?? {};
        this.symbolGroups.set(MARKETS.filter((m) => by[m.code]?.length).map((m) => ({ market: m.code, label: m.label, symbols: by[m.code] })));
      },
      error: () => {},
    });
    this.load();
    this.timer = setInterval(() => this.load(), 30_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected pick(s: string): void {
    this.symbol.set(s);
    try { localStorage.setItem('jev.universe.symbol', s); } catch { /* sem armazenamento */ }
    this.selected.set(null);
    this.load();
  }

  protected setMode(m: Mode): void {
    this.mode.set(m);
    this.selected.set(null);
    this.load();
  }

  private load(): void {
    this.http.get<any>(`/api/universe?symbol=${encodeURIComponent(this.symbol())}&mode=${this.mode()}`).subscribe({
      next: (d) => { this.data.set(d); this.error.set(null); },
      error: () => this.error.set('Universo indisponível: reinicie o servidor com o jar novo.'),
    });
  }

  protected open(url?: string): void {
    if (url) window.open(url, '_blank', 'noopener');
  }

  // ------------------------------------------------------------------ o planeta

  private move24(): number | null {
    const m = (this.data()?.planet?.moves ?? []).find((x: any) => x.window === '24h');
    return m?.pct ?? null;
  }

  protected planetLight = computed(() => { const m = this.move24(); return m === null ? '#cbd5e1' : m >= 0 ? '#a7f3d0' : '#fecaca'; });
  protected planetDark = computed(() => { const m = this.move24(); return m === null ? '#475569' : m >= 0 ? '#047857' : '#b91c1c'; });

  protected priceText(): string {
    const p = this.data()?.planet?.price;
    return p == null ? '' : String(p);
  }

  protected moveText(): string {
    const m = this.move24(), rsi = this.data()?.planet?.rsi_m15;
    const parts = [];
    if (m !== null) parts.push(`24 h ${m >= 0 ? '+' : ''}${m.toFixed(2)}%`);
    if (rsi != null) parts.push(`RSI ${Math.round(rsi)}`);
    return parts.join(' · ');
  }

  // ------------------------------------------------------------------ as órbitas do tempo

  /** Raio da órbita de um instante (o planeta tem ~66 px; as luas, 92 px). */
  private radius(iso: string): number {
    const now = Date.now(), t = new Date(iso).getTime();
    if (this.mode() === 'present') return 125 + Math.min(1, Math.max(0, (t - now) / 3_600_000)) * 235;
    if (this.mode() === 'past') return 125 + Math.min(1, Math.max(0, (now - t) / (48 * 3_600_000))) * 235;
    const h = Math.max(1, (t - now) / 3_600_000);
    return 125 + Math.min(1, Math.log(h) / Math.log(168)) * 235;
  }

  protected rings = computed(() => {
    const m = this.mode();
    const at = (ms: number) => this.radiusAt(ms);
    if (m === 'present') return [15, 30, 45, 60].map((x) => ({ r: at(x * 60_000), label: `${x} min` }));
    if (m === 'past') return [6, 12, 24, 48].map((x) => ({ r: at(-x * 3_600_000), label: `${x} h atrás` }));
    return [[3, '3 h'], [12, '12 h'], [24, '1 dia'], [72, '3 dias'], [168, '7 dias']].map(([h, l]) => ({ r: at(+h * 3_600_000), label: String(l) }));
  });

  private radiusAt(deltaMs: number): number {
    return this.radius(new Date(Date.now() + deltaMs).toISOString());
  }

  // ------------------------------------------------------------------ os corpos

  private visibleEvents = computed(() => (this.data()?.asteroids ?? [])
    .filter((e: any) => this.mode() === 'present' || this.showLow() || e.importance === 'HIGH' || e.importance === 'MODERATE'));

  private colorOf(e: any): { color: string; ring: string | null; dash: boolean } {
    if (this.mode() === 'past') {
      const o = e.outcome?.['15m'];
      const color = !o?.label ? GRAY : o.label === 'ALTA' ? GREEN : o.label === 'QUEDA' ? RED : GRAY;
      return { color, ring: o?.hit === undefined || o?.hit === null ? null : '#ffffff', dash: o?.hit === false };
    }
    const d = e.lean?.dir;
    return { color: d === 'up' ? GREEN : d === 'down' ? RED : GRAY, ring: null, dash: false };
  }

  protected asteroids = computed<Body[]>(() => this.visibleEvents().map((e: any) => {
    const key = `${e.event_code}|${e.at}`;
    const r = this.radius(e.at), a = angleOf(key) * Math.PI / 180;
    const c = this.colorOf(e);
    return { key, kind: 'asteroid', x: C + r * Math.cos(a), y: C + r * Math.sin(a), r: SIZE[e.importance] ?? 5,
      orbit: r, dur: Math.round(r * 2.2), angle: a, ...c, title: this.titleOf(e) } as Body;
  }));

  protected moons = computed<Body[]>(() => (this.data()?.moons ?? []).map((p: any, i: number) => {
    const n = (this.data()?.moons ?? []).length || 1;
    const a = (i / n) * 2 * Math.PI, r = 92;
    const top = Math.max(p.pUp, p.pDown, p.pFlat);
    const color = p.pFlat >= Math.max(p.pUp, p.pDown) ? GRAY : p.pUp > p.pDown ? GREEN : RED;
    const side = p.pFlat >= Math.max(p.pUp, p.pDown) ? 'LATERAL' : p.pUp > p.pDown ? 'ALTA' : 'QUEDA';
    return { key: `${p.model}${p.horizonMin}`, kind: 'moon', x: C + r * Math.cos(a), y: C + r * Math.sin(a), r: 6 + top * 8,
      color, ring: null, dash: false, orbit: r, dur: 60, angle: a, label: `${p.model}${p.horizonMin === 15 ? '¹' : '⁶'}`,
      title: `Lua: ${MODEL_NAMES[p.model] ?? p.model}, ${p.horizonMin} min — ${side} mais provável\nalta ${(p.pUp * 100).toFixed(0)}% · lateral ${(p.pFlat * 100).toFixed(0)}% · queda ${(p.pDown * 100).toFixed(0)}%`
        + (p.signal && p.signal !== 'NO_TRADE' ? `\nSINAL: ${p.signal === 'BUY' ? 'COMPRA' : 'VENDA'}` : '') + `\nprevisão de ${brt(p.momentUtc)}` } as Body;
  }));

  protected comets = computed<Body[]>(() => (this.data()?.comets ?? []).map((n: any) => {
    const key = n.url ?? n.title;
    const ageH = Math.min(48, Math.max(0, (Date.now() - new Date(n.at).getTime()) / 3_600_000));
    const r = 370 + (ageH / 48) * 20, a = angleOf(key) * Math.PI / 180;
    const x = C + r * Math.cos(a), y = C + r * Math.sin(a);
    return { key, kind: 'comet', x, y, r: 4, color: '#bfdbfe', ring: null, dash: false, orbit: r, dur: 0, angle: a,
      tailX: C + (r - 30) * Math.cos(a), tailY: C + (r - 30) * Math.sin(a), url: n.url,
      title: `Cometa (notícia, ${n.issuer ?? n.currency}): ${n.title}\n${brt(n.at)} · clique para abrir` } as Body;
  }));

  protected stars = computed<Body[]>(() => (this.data()?.planet?.stars ?? []).map((s: any, i: number) => {
    const key = `${s.kind}${s.tf}${i}`, a = angleOf(key) * Math.PI / 180, r = 385;
    const color = s.dir === 'up' ? GREEN : s.dir === 'down' ? RED : GOLD;
    return { key, kind: 'star', x: C + r * Math.cos(a), y: C + r * Math.sin(a), r: 2 + s.severity, color, ring: null,
      dash: false, orbit: r, dur: 0, angle: a, title: `Estrela (radar, ${s.tf}): ${s.text}` } as Body;
  }));

  // ------------------------------------------------------------------ textos

  private titleOf(e: any): string {
    const imp = e.importance === 'HIGH' ? 'alta' : e.importance === 'MODERATE' ? 'moderada' : 'baixa';
    const lines = [`${e.name} (${e.currency}, importância ${imp})`, brt(e.at)];
    const nums = [e.forecast != null ? `previsto ${e.forecast}` : null, e.previous != null ? `anterior ${e.previous}` : null,
      e.actual != null ? `real ${e.actual}` : null].filter(Boolean);
    if (nums.length) lines.push(nums.join(' · '));
    if (this.mode() === 'past') {
      const o15 = e.outcome?.['15m'], o60 = e.outcome?.['60m'];
      if (o15?.label) lines.push(`depois (15 min): ${o15.label} · modelo disse ${o15.said}${o15.hit ? ' ✓' : ' ✗'}`);
      if (o60?.label) lines.push(`depois (60 min): ${o60.label} · modelo disse ${o60.said}${o60.hit ? ' ✓' : ' ✗'}`);
      if (!o15?.label && !o60?.label) lines.push('sem previsão de evento resolvida para este símbolo');
    } else {
      if (e.lean?.why) lines.push(e.lean.why);
      const h = e.history;
      if (h?.n) lines.push(`histórico: ${h.n} vezes, anda ${(h.abs15 ?? 0).toFixed(1)} ATR em 15 min`);
    }
    return lines.join('\n');
  }

  protected list = computed(() => this.visibleEvents().map((e: any) => {
    const t = new Date(e.at).getTime(), mins = Math.round((t - Date.now()) / 60_000);
    const when = mins >= 0 ? (mins < 120 ? `em ${mins} min` : `em ${Math.round(mins / 60)} h`) : `há ${Math.round(-mins / 60) || 1} h`;
    return { key: `${e.event_code}|${e.at}`, name: e.name, currency: e.currency, importance: e.importance,
      color: this.colorOf(e).color, when: `${when} · ${brt(e.at)}`, detail: this.titleOf(e) };
  }));
}
