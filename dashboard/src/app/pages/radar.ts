import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Api, CalendarItem, LatestPredictions, NewsItem, Prediction, ScanAlert, ScanSnapshot, ScanSymbol, ScanTf } from '../api';
import { Tip } from './tip';
import { Brt } from '../brt';
import { MarketState } from '../market';

/** Uma linha do feed: alerta de preço (do servidor) ou de calendário/notícia (montado aqui). */
interface FeedItem {
  key: string;
  symbol: string | null;
  currency: string | null;
  kind: string;
  tf: string;
  severity: number;
  dir: 'up' | 'down' | null;
  text: string;
  at: string | null;
  url?: string;
}

const KINDS: { code: string; label: string; help: string }[] = [
  { code: 'MOVE', label: 'Movimento', help: 'radarMove' },
  { code: 'CANDLE', label: 'Barra grande', help: 'radarCandle' },
  { code: 'VOLUME', label: 'Volume', help: 'radarVolume' },
  { code: 'RSI', label: 'RSI', help: 'radarRsi' },
  { code: 'BB', label: 'Bollinger', help: 'radarBb' },
  { code: 'SQUEEZE', label: 'Compressão', help: 'radarSqueeze' },
  { code: 'STREAK', label: 'Sequência', help: 'radarStreak' },
  { code: 'EMA', label: 'Médias', help: 'radarEma' },
  { code: 'BREAKOUT', label: 'Rompimento', help: 'radarBreakout' },
  { code: 'RANGE', label: 'Amplitude', help: 'radarRange' },
  { code: 'GAP', label: 'Gap', help: 'radarGap' },
  { code: 'SPREAD', label: 'Spread', help: 'radarSpread' },
  { code: 'CAL', label: 'Calendário', help: 'radarCal' },
  { code: 'NEWS', label: 'Notícia', help: 'radarNews' },
];
const KIND_LABEL: Record<string, string> = Object.fromEntries(KINDS.map((k) => [k.code, k.label]));
const TFS = ['M5', 'M15', 'H1', 'H4', 'D1'];
/** Janela de um movimento → o período com que ela se compara no filtro. */
const WINDOW_TF: Record<string, string> = { '15m': 'M15', '1h': 'H1', '4h': 'H4', '24h': 'D1', dia: 'D1', M1: 'M5' };
const STALE_MIN = 10;

@Component({
  selector: 'page-radar',
  imports: [DatePipe, DecimalPipe, Tip, Brt],
  template: `
    <div class="notice">
      <b>Radar = o que o preço está fazendo agora, não sinal.</b> Nenhum destes alertas foi validado como entrada: a volta
      às bandas de Bollinger, por exemplo, deu resultado médio negativo nos 5 mercados no teste de 13/10. Use como
      contexto junto da previsão e das travas de risco.
    </div>

    <section class="panel bar">
      <div class="status small">
        @if (snap(); as s) {
          <span>Atualizado <span class="mono" [brt]="s.generatedAt">{{ s.generatedAt | date: 'HH:mm:ss' : 'UTC' }} UTC</span></span>
          <span>· última barra <span class="mono" [brt]="s.lastBarUtc">{{ s.lastBarUtc | date: 'dd/MM HH:mm' : 'UTC' }}</span>
            @if (ageMin(s.lastBarUtc) > ${STALE_MIN}) { <span class="badge warn">parado há {{ ageText(s.lastBarUtc) }}: mercado fechado ou MT5 parado</span> }
          </span>
        } @else if (error()) {
          <span class="down">{{ error() }}</span>
        } @else {
          <span class="muted">Carregando…</span>
        }
        <button (click)="reload()" [disabled]="loading()">{{ loading() ? 'Lendo…' : 'Atualizar' }}</button>
      </div>
      <div class="filters">
        <span class="small muted">Intensidade <tip key="radarIntensidade" /></span>
        @for (o of sevOptions; track o.v) {
          <button class="chip" [class.on]="minSev() === o.v" (click)="setMinSev(o.v)">{{ o.label }}</button>
        }
        <span class="sep"></span>
        <span class="small muted">Período</span>
        @for (t of tfList; track t) {
          <button class="chip" [class.on]="tfOn(t)" (click)="toggleTf(t)">{{ t }}</button>
        }
        <span class="sep"></span>
        <span class="small muted">Símbolo</span>
        <select [value]="selected() ?? ''" (change)="select($any($event.target).value || null)">
          <option value="">todos</option>
          @for (s of snap()?.symbols ?? []; track s.symbol) { <option [value]="s.symbol">{{ s.symbol }}</option> }
        </select>
      </div>
      <div class="filters">
        <span class="small muted">Tipos</span>
        @for (k of kinds; track k.code) {
          <button class="chip" [class.on]="kindOn(k.code)" (click)="toggleKind(k.code)">{{ k.label }}</button>
        }
        @if (kindFilter().size) { <button class="chip" (click)="kindFilter.set(emptySet())">todos</button> }
      </div>
    </section>

    <div class="grid two" style="margin-top:16px">
      <section class="panel">
        <h3>Alertas agora ({{ feed().length }}) <tip key="radarAlertas" /></h3>
        <div class="table-wrap feed"><table>
          <tr><th>Quando (UTC)</th><th>Símbolo</th><th>Tipo</th><th>Período</th><th>O que aconteceu</th><th></th></tr>
          @for (a of feedShown(); track a.key) {
            <tr [class.sel]="a.symbol && a.symbol === selected()">
              <td class="nowrap mono small" [brt]="a.at">{{ a.at | date: 'dd/MM HH:mm' : 'UTC' }}<div class="muted">{{ relative(a.at) }}</div></td>
              <td class="nowrap">
                @if (a.symbol) { <a class="mono" (click)="select(a.symbol)">{{ a.symbol }}</a> }
                @else { <span class="badge">{{ a.currency }}</span> }
              </td>
              <td class="nowrap"><span class="badge" [class.buy]="a.dir === 'up'" [class.sell]="a.dir === 'down'">{{ label(a.kind) }}</span></td>
              <td class="small">{{ a.tf }}</td>
              <td>@if (a.url) { <a [href]="a.url" target="_blank" rel="noopener">{{ a.text }}</a> } @else { {{ a.text }} }</td>
              <td class="nowrap sev" [title]="sevTitle(a.severity)">{{ dots(a.severity) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="muted">Nenhum alerta com estes filtros.</td></tr>
          }
        </table></div>
        @if (feed().length > feedShown().length) {
          <button (click)="showAll.set(true)">Mostrar todos ({{ feed().length }})</button>
        }
      </section>

      <section class="panel">
        <h3>Próximos eventos{{ selected() ? ' · ' + selectedCurrencies().join(', ') : '' }} <tip key="radarCal" /></h3>
        <div class="table-wrap"><table>
          <tr><th>Quando (UTC)</th><th>Em</th><th>Moeda</th><th>Evento</th><th class="right">Previsto</th><th class="right">Atual</th></tr>
          @for (e of marketEvents(); track e.value_id) {
            <tr [class.muted]="isPast(e)">
              <td class="nowrap mono small" [brt]="e.scheduled_at">{{ e.scheduled_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="nowrap small" [class.soon]="minutesTo(e.scheduled_at) >= 0 && minutesTo(e.scheduled_at) <= 30">{{ relative(e.scheduled_at) }}</td>
              <td><span class="badge" [class.high]="e.importance === 'HIGH'">{{ e.currency }}</span></td>
              <td>{{ e.name }}</td>
              <td class="right mono">{{ e.forecast ?? '—' }}</td>
              <td class="right mono" [class.up]="surprise(e) > 0" [class.down]="surprise(e) < 0">{{ e.actual ?? '—' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="muted">Nenhum evento de importância média ou alta nas próximas 48 h para estas moedas.</td></tr>
          }
        </table></div>
        <h3 style="margin-top:16px">Últimas notícias dos bancos centrais <tip key="radarNews" /></h3>
        <div class="table-wrap"><table>
          @for (n of marketNews(); track n.id) {
            <tr>
              <td class="nowrap mono small" [brt]="n.first_seen_at">{{ n.first_seen_at | date: 'dd/MM HH:mm' : 'UTC' }}<div class="muted">{{ relative(n.first_seen_at) }}</div></td>
              <td class="nowrap">{{ n.issuer }} <span class="muted small">{{ n.currency }}</span></td>
              <td><a [href]="n.url" target="_blank" rel="noopener">{{ n.title }}</a></td>
              <td class="nowrap">
                @if (n.jev; as j) {
                  <span class="badge" [class.buy]="j.stance > 0.15" [class.sell]="j.stance < -0.15">
                    {{ j.stance > 0.15 ? 'hawkish' : j.stance < -0.15 ? 'dovish' : 'neutro' }}</span>
                }
              </td>
            </tr>
          } @empty {
            <tr><td class="muted">Sem documentos recentes para estas moedas.</td></tr>
          }
        </table></div>
      </section>
    </div>

    <section class="panel" style="margin-top:16px">
      <h3>Mapa por símbolo <tip key="radarMapa" /></h3>
      <div class="table-wrap"><table class="map">
        <tr>
          <th rowspan="2">Símbolo</th><th rowspan="2" class="right">Preço</th>
          <th colspan="4" class="center">Variação (% · ATR) <tip key="radarMove" /></th>
          <th colspan="5" class="center">RSI 14 · Bollinger 20,2 <tip key="radarRsi" /></th>
          <th colspan="2" class="center">Volume <tip key="radarVolume" /></th>
          <th rowspan="2">Spread <tip key="radarSpread" /></th>
          <th rowspan="2">Próximo evento</th>
          <th rowspan="2">Previsão <tip key="radarPrevisao" /></th>
          <th rowspan="2">Alertas</th>
        </tr>
        <tr>
          <th class="right">15m</th><th class="right">1h</th><th class="right">4h</th><th class="right">dia</th>
          @for (t of tfList; track t) { <th class="center">{{ t }}</th> }
          <th class="right">M15</th><th class="right">H1</th>
        </tr>
        @for (s of snap()?.symbols ?? []; track s.symbol) {
          <tr class="row" [class.sel]="s.symbol === selected()" [class.stale]="ageMin(s.lastBarUtc) > ${STALE_MIN}" (click)="select(s.symbol)">
            <td class="mono nowrap"><b>{{ s.symbol }}</b>
              @if (ageMin(s.lastBarUtc) > ${STALE_MIN}) { <div class="small muted">parado há {{ ageText(s.lastBarUtc) }}</div> }</td>
            <td class="right mono">{{ px(s.price) }}</td>
            @for (w of moveWindows; track w) {
              @let m = move(s, w);
              <td class="right mono nowrap" [class.up]="(m?.pct ?? 0) > 0" [class.down]="(m?.pct ?? 0) < 0" [class.strong]="Math.abs(m?.atrs ?? 0) >= 2">
                {{ m?.pct === null || m?.pct === undefined ? '—' : (m!.pct! | number: '1.2-2') + '%' }}
                <div class="small">{{ m?.atrs === null || m?.atrs === undefined ? '' : (m!.atrs! | number: '1.1-1') + ' ATR' }}</div>
              </td>
            }
            @for (t of tfList; track t) {
              @let st = tf(s, t);
              <td class="center mono rsi" [class]="rsiClass(st)" [title]="tfTitle(st)">
                {{ st?.rsi === null || st?.rsi === undefined ? '—' : (st!.rsi! | number: '1.0-0') }}<span class="mark">{{ bbMark(st) }}</span>
              </td>
            }
            @for (t of ['M15', 'H1']; track t) {
              @let st = tf(s, t);
              <td class="right mono" [class.hot]="(st?.volRatio ?? 0) >= 2.5">{{ st?.volRatio === null || st?.volRatio === undefined ? '—' : (st!.volRatio! | number: '1.1-1') + '×' }}</td>
            }
            <td class="mono nowrap" [class.hot]="(s.spreadRatio ?? 0) >= 2">{{ s.spreadNow ?? '—' }}
              <span class="small muted">{{ s.spreadRatio === null ? '' : (s.spreadRatio | number: '1.1-1') + '×' }}</span></td>
            <td class="small">
              @if (nextEvent(s); as e) {
                <span class="badge" [class.high]="e.importance === 'HIGH'">{{ e.currency }}</span>
                <span [class.soon]="minutesTo(e.scheduled_at) <= 30"> {{ relative(e.scheduled_at) }}</span>
                <div class="muted ellipsis" [title]="e.name">{{ e.name }}</div>
              } @else { <span class="muted">—</span> }
            </td>
            <td class="nowrap small">
              @if (prediction(s.symbol); as p) {
                <span class="badge" [class.buy]="p.pUp > p.pDown" [class.sell]="p.pDown > p.pUp" [title]="predTitle(p)">
                  {{ p.pUp > p.pDown ? '▲' : '▼' }} {{ Math.abs(p.pUp - p.pDown) * 100 | number: '1.0-0' }}</span>
                @if (p.signal !== 'NO_TRADE') { <b> sinal</b> }
              } @else { <span class="muted">—</span> }
            </td>
            <td class="nowrap sev">
              @let c = counts(s);
              @if (c[3]) { <span class="s3">●{{ c[3] }}</span> }
              @if (c[2]) { <span class="s2">●{{ c[2] }}</span> }
              @if (c[1]) { <span class="s1">●{{ c[1] }}</span> }
            </td>
          </tr>
        } @empty {
          <tr><td colspan="18" class="muted">{{ loading() ? 'Lendo os candles…' : 'Sem dados deste mercado.' }}</td></tr>
        }
      </table></div>
      <p class="small muted legend">RSI: <span class="rsi c-up2">≥ 80</span> <span class="rsi c-up1">≥ 70</span>
        <span class="rsi c-down1">≤ 30</span> <span class="rsi c-down2">≤ 20</span> · ▲ fechou acima da banda superior de
        Bollinger · ▼ abaixo da inferior · ◆ bandas na menor largura de 120 barras · tudo na última barra fechada de cada período.
        Clique numa linha para ver o detalhe.</p>
    </section>

    @if (strength().length) {
      <section class="panel" style="margin-top:16px">
        <h3>Força das moedas <tip key="radarForca" /></h3>
        <div class="strength">
          @for (w of strength(); track w.window) {
            <div>
              <div class="small muted">{{ w.window === '1h' ? 'última hora' : 'últimas ' + w.window.replace('h', ' h') }}</div>
              @for (c of w.items; track c.ccy) {
                <div class="sbar">
                  <span class="mono">{{ c.ccy }}</span>
                  <span class="track"><span class="fill" [class.pos]="c.v > 0" [class.neg]="c.v < 0"
                    [style.width.%]="50 * Math.abs(c.v) / w.max" [style.left.%]="c.v >= 0 ? 50 : 50 - 50 * Math.abs(c.v) / w.max"></span></span>
                  <span class="mono small right" [class.up]="c.v > 0" [class.down]="c.v < 0">{{ c.v | number: '1.2-2' }}%</span>
                </div>
              }
            </div>
          }
        </div>
      </section>
    }

    @if (detail(); as s) {
      <section class="panel" style="margin-top:16px">
        <h3>Detalhe · {{ s.symbol }} <span class="muted small">({{ s.currencies.join(' · ') }})</span>
          <button class="close" (click)="select(null)">fechar</button></h3>
        <div class="grid two">
          <div>
            <div class="table-wrap"><table>
              <tr><th>Período</th><th class="right">Fechamento</th><th class="right">RSI <tip text="Na última barra fechada; entre parênteses, contando a barra em formação (o que o gráfico do MT5 mostra agora)." /></th>
                <th class="right">%B <tip key="radarBb" /></th><th class="right">Bandas (inf · sup)</th><th class="right">ATR</th>
                <th class="right">Vol.</th><th class="right">Seq. <tip key="radarStreak" /></th><th>MME 20/50 <tip key="radarEma" /></th></tr>
              @for (t of s.tfs; track t.tf) {
                <tr>
                  <td>{{ t.tf }}</td>
                  <td class="right mono">{{ px(t.close) }}</td>
                  <td class="right mono" [class]="rsiClass(t)">{{ t.rsi === null ? '—' : (t.rsi | number: '1.0-0') }} <span class="small">({{ t.rsiNow === null ? '—' : (t.rsiNow | number: '1.0-0') }})</span></td>
                  <td class="right mono" [class.up]="(t.pctB ?? 0.5) > 1" [class.down]="(t.pctB ?? 0.5) < 0">{{ t.pctB === null ? '—' : (t.pctB | number: '1.2-2') }}{{ t.squeeze ? ' ◆' : '' }}</td>
                  <td class="right mono small">{{ px(t.lower) }} · {{ px(t.upper) }}</td>
                  <td class="right mono small">{{ px(t.atr) }}</td>
                  <td class="right mono" [class.hot]="(t.volRatio ?? 0) >= 2.5">{{ t.volRatio === null ? '—' : (t.volRatio | number: '1.1-1') + '×' }}</td>
                  <td class="right mono" [class.up]="t.streak > 0" [class.down]="t.streak < 0">{{ t.streak > 0 ? '+' : '' }}{{ t.streak }}</td>
                  <td>@if (t.trend) { <span class="badge" [class.buy]="t.trend === 'up'" [class.sell]="t.trend === 'down'">{{ t.trend === 'up' ? 'alta' : 'baixa' }}</span> } @else { <span class="muted small">—</span> }</td>
                </tr>
              }
            </table></div>
            <table class="day">
              <tr><th>Hoje (UTC)</th><td class="mono">abertura {{ px(s.day.open) }} · máx. {{ px(s.day.high) }} · mín. {{ px(s.day.low) }}</td></tr>
              <tr><th>Dia anterior</th><td class="mono">máx. {{ px(s.day.prevHigh) }} · mín. {{ px(s.day.prevLow) }}</td></tr>
              <tr><th>20 dias</th><td class="mono">máx. {{ px(s.day.high20) }} · mín. {{ px(s.day.low20) }}</td></tr>
              <tr><th>Amplitude <tip key="radarRange" /></th><td class="mono">hoje {{ s.day.rangeAdr === null ? '—' : (s.day.rangeAdr | number: '1.1-1') + '×' }} a média de 20 dias ({{ px(s.day.adr) }})</td></tr>
            </table>
          </div>
          <div>
            <h3>Alertas de {{ s.symbol }}</h3>
            <table>
              @for (a of s.alerts; track $index) {
                <tr>
                  <td class="nowrap small mono" [brt]="a.at">{{ a.at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
                  <td><span class="badge" [class.buy]="a.dir === 'up'" [class.sell]="a.dir === 'down'">{{ label(a.kind) }}</span></td>
                  <td>{{ a.text }}</td>
                  <td class="sev">{{ dots(a.severity) }}</td>
                </tr>
              } @empty {
                <tr><td class="muted">Nada fora do normal agora.</td></tr>
              }
            </table>
          </div>
        </div>
      </section>
    }
  `,
  styles: `
    .bar { display: grid; gap: 8px; }
    .status { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .status button { margin-left: auto; }
    .filters { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .filters select { font: inherit; padding: 3px 6px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .chip { padding: 2px 10px; border-radius: 999px; font-size: 12px; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: #fff; }
    .sep { width: 10px; }
    .feed { max-height: 560px; overflow-y: auto; }
    .sev { letter-spacing: 1px; color: var(--warn); font-size: 12px; }
    .sev .s3 { color: var(--down); margin-right: 6px; }
    .sev .s2 { color: var(--warn); margin-right: 6px; }
    .sev .s1 { color: var(--muted); }
    a { cursor: pointer; }
    tr.sel td { background: var(--panel-2); }
    tr.row { cursor: pointer; }
    tr.row:hover td { background: var(--panel-2); }
    tr.stale td { opacity: .55; }
    .map th, .map td { padding: 4px 6px; }
    .center { text-align: center; }
    .strong { font-weight: 700; }
    .hot { color: var(--warn); font-weight: 700; }
    .soon { color: var(--down); font-weight: 700; }
    .ellipsis { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .rsi { border-radius: 4px; }
    .rsi .mark { font-size: 10px; margin-left: 2px; }
    .c-up1 { background: var(--up-bg); color: var(--up); }
    .c-up2 { background: var(--up); color: var(--panel); }
    .c-down1 { background: var(--down-bg); color: var(--down); }
    .c-down2 { background: var(--down); color: var(--panel); }
    .legend .rsi { padding: 0 6px; }
    .strength { display: grid; gap: 18px; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
    .sbar { display: grid; grid-template-columns: 36px 1fr 56px; gap: 6px; align-items: center; margin: 3px 0; }
    .track { position: relative; height: 10px; background: var(--panel-2); border-radius: 4px; }
    .track::after { content: ''; position: absolute; left: 50%; top: -2px; bottom: -2px; width: 1px; background: var(--border); }
    .fill { position: absolute; top: 0; bottom: 0; border-radius: 4px; }
    .fill.pos { background: var(--up); }
    .fill.neg { background: var(--down); }
    .close { float: right; font-size: 12px; padding: 2px 10px; text-transform: none; letter-spacing: 0; }
    .day { margin-top: 10px; }
    .day th { width: 130px; }
  `,
})
export class Radar implements OnInit, OnDestroy {
  private api = inject(Api);
  private marketState = inject(MarketState);
  protected Math = Math;
  protected kinds = KINDS;
  protected tfList = TFS;
  protected moveWindows = ['15m', '1h', '4h', 'dia'];
  protected sevOptions = [{ v: 1, label: 'todas' }, { v: 2, label: 'notáveis e fortes' }, { v: 3, label: 'só fortes' }];

  protected snap = signal<ScanSnapshot | null>(null);
  protected events = signal<CalendarItem[]>([]);
  protected news = signal<NewsItem[]>([]);
  protected latest = signal<LatestPredictions | null>(null);
  protected loading = signal(false);
  protected error = signal<string | null>(null);
  protected now = signal(Date.now());
  protected selected = signal<string | null>(null);
  protected minSev = signal<number>(this.loadSev());
  protected kindFilter = signal<Set<string>>(new Set());
  protected tfFilter = signal<Set<string>>(new Set());
  protected showAll = signal(false);

  private timer?: ReturnType<typeof setInterval>;
  private slowTimer?: ReturnType<typeof setInterval>;
  private clock?: ReturnType<typeof setInterval>;

  /** Moedas do mercado (as do símbolo escolhido, se houver). */
  protected selectedCurrencies = computed(() => {
    const s = this.snap()?.symbols ?? [];
    const sel = this.selected();
    const list = sel ? s.filter((x) => x.symbol === sel) : s;
    return [...new Set(list.flatMap((x) => x.currencies))];
  });

  protected detail = computed(() => this.snap()?.symbols.find((s) => s.symbol === this.selected()) ?? null);

  protected marketEvents = computed(() => {
    const ccy = new Set(this.selectedCurrencies());
    const now = this.now();
    return this.events().filter((e) => ccy.has(e.currency) && new Date(e.scheduled_at).getTime() >= now - 2 * 3600_000)
      .slice(0, 25);
  });

  protected marketNews = computed(() => {
    const ccy = new Set(this.selectedCurrencies());
    return this.news().filter((n) => ccy.has(n.currency)).slice(0, 8);
  });

  /** Alertas de preço do servidor + calendário (até 2 h à frente, até 1 h atrás) + notícias das últimas 6 h. */
  protected feed = computed<FeedItem[]>(() => {
    const snap = this.snap();
    if (!snap) return [];
    const now = this.now();
    const items: FeedItem[] = [];
    for (const s of snap.symbols) {
      s.alerts.forEach((a, i) => items.push({ key: `${s.symbol}-${a.kind}-${a.tf}-${i}`, symbol: s.symbol, currency: null,
        kind: a.kind, tf: a.tf, severity: a.severity, dir: a.dir, text: a.text, at: a.at }));
    }
    const ccy = new Set(snap.symbols.flatMap((s) => s.currencies));
    for (const e of this.events()) {
      if (!ccy.has(e.currency)) continue;
      const min = (new Date(e.scheduled_at).getTime() - now) / 60_000;
      const high = e.importance === 'HIGH';
      if (min >= 0 && min <= 120) {
        items.push({ key: `cal-${e.value_id}`, symbol: null, currency: e.currency, kind: 'CAL', tf: 'evento',
          severity: high ? (min <= 15 ? 3 : min <= 60 ? 2 : 1) : (min <= 15 ? 2 : 1), dir: null, at: e.scheduled_at,
          text: `${e.name} (${e.currency}) em ${Math.round(min)} min` + (e.forecast !== null ? ` · previsto ${e.forecast}` : '') });
      } else if (min < 0 && min >= -60 && e.actual !== null) {
        const s = this.surprise(e);
        items.push({ key: `cal-${e.value_id}`, symbol: null, currency: e.currency, kind: 'CAL', tf: 'evento',
          severity: high ? 2 : 1, dir: null, at: e.scheduled_at,
          text: `${e.name} (${e.currency}) saiu ${e.actual}` + (e.forecast !== null
            ? ` × ${e.forecast} previsto (${s > 0 ? 'acima' : s < 0 ? 'abaixo' : 'igual'})` : '') });
      }
    }
    for (const n of this.news()) {
      if (!ccy.has(n.currency) || now - new Date(n.first_seen_at).getTime() > 6 * 3600_000) continue;
      items.push({ key: `news-${n.id}`, symbol: null, currency: n.currency, kind: 'NEWS', tf: n.issuer,
        severity: n.jev && n.jev.relevance >= 0.7 ? 2 : 1, dir: null, at: n.first_seen_at, url: n.url,
        text: `${n.issuer}: ${n.title}` + (n.jev ? ` · Jev: ${n.jev.stance > 0.15 ? 'hawkish' : n.jev.stance < -0.15 ? 'dovish' : 'neutro'}` : '') });
    }
    const sel = this.selected();
    const selCcy = new Set(this.selectedCurrencies());
    const kinds = this.kindFilter(), tfs = this.tfFilter(), minSev = this.minSev();
    return items
      .filter((a) => a.severity >= minSev)
      .filter((a) => !kinds.size || kinds.has(a.kind))
      .filter((a) => !tfs.size || a.kind === 'CAL' || a.kind === 'NEWS' || tfs.has(WINDOW_TF[a.tf] ?? a.tf))
      .filter((a) => !sel || a.symbol === sel || (a.symbol === null && a.currency !== null && selCcy.has(a.currency)))
      .sort((a, b) => b.severity - a.severity || this.distance(a.at, now) - this.distance(b.at, now));
  });

  protected feedShown = computed(() => (this.showAll() ? this.feed() : this.feed().slice(0, 60)));

  /** Força das moedas por janela, com a escala da maior barra. */
  protected strength = computed(() => Object.entries(this.snap()?.strength ?? {}).map(([window, m]) => {
    const items = Object.entries(m).map(([ccy, v]) => ({ ccy, v }));
    return { window, items, max: Math.max(0.01, ...items.map((x) => Math.abs(x.v))) };
  }));

  constructor() {
    effect(() => {
      this.marketState.market();
      this.snap.set(null);
      this.selected.set(null);
      this.showAll.set(false);
      this.reload();
    });
  }

  ngOnInit(): void {
    this.timer = setInterval(() => this.load(), 60_000);
    this.slowTimer = setInterval(() => this.loadSlow(), 120_000);
    this.clock = setInterval(() => this.now.set(Date.now()), 15_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.slowTimer);
    clearInterval(this.clock);
  }

  protected reload(): void {
    this.load();
    this.loadSlow();
  }

  private load(): void {
    const market = this.marketState.market();
    this.loading.set(true);
    this.api.scanner(market).subscribe({
      next: (s) => { if (s.market === this.marketState.market()) this.snap.set(s); this.error.set(null); this.loading.set(false); },
      error: (e) => { this.error.set('Radar indisponível: ' + (e?.error?.message ?? e?.message ?? 'erro')); this.loading.set(false); },
    });
    this.api.latest(market).subscribe({ next: (l) => this.latest.set(l), error: () => this.latest.set(null) });
    this.now.set(Date.now());
  }

  private loadSlow(): void {
    this.api.upcoming(48).subscribe({ next: (e) => this.events.set(e), error: () => this.events.set([]) });
    this.api.news(80).subscribe({ next: (n) => this.news.set(n), error: () => this.news.set([]) });
  }

  // ------------------------------------------------------------------ filtros
  protected emptySet(): Set<string> {
    return new Set();
  }

  protected select(symbol: string | null): void {
    this.selected.set(this.selected() === symbol ? null : symbol);
  }

  protected setMinSev(v: number): void {
    this.minSev.set(v);
    try { localStorage.setItem('jev.radar.sev', String(v)); } catch { /* sem armazenamento: só nesta aba */ }
  }

  private loadSev(): number {
    try { return Number(localStorage.getItem('jev.radar.sev')) || 1; } catch { return 1; }
  }

  protected kindOn(k: string): boolean {
    return this.kindFilter().has(k);
  }

  protected toggleKind(k: string): void {
    const s = new Set(this.kindFilter());
    if (s.has(k)) s.delete(k); else s.add(k);
    this.kindFilter.set(s);
  }

  protected tfOn(t: string): boolean {
    return this.tfFilter().has(t);
  }

  protected toggleTf(t: string): void {
    const s = new Set(this.tfFilter());
    if (s.has(t)) s.delete(t); else s.add(t);
    this.tfFilter.set(s);
  }

  // ------------------------------------------------------------------ leitura
  protected label(kind: string): string {
    return KIND_LABEL[kind] ?? kind;
  }

  protected dots(sev: number): string {
    return '●'.repeat(sev) + '○'.repeat(3 - sev);
  }

  protected sevTitle(sev: number): string {
    return sev === 3 ? 'forte' : sev === 2 ? 'notável' : 'informativo';
  }

  protected move(s: ScanSymbol, w: string) {
    return s.moves.find((m) => m.window === w) ?? null;
  }

  protected tf(s: ScanSymbol, t: string): ScanTf | null {
    return s.tfs.find((x) => x.tf === t) ?? null;
  }

  protected rsiClass(t: ScanTf | null): string {
    const r = t?.rsi;
    if (r === null || r === undefined) return 'rsi';
    return 'rsi ' + (r >= 80 ? 'c-up2' : r >= 70 ? 'c-up1' : r <= 20 ? 'c-down2' : r <= 30 ? 'c-down1' : '');
  }

  protected bbMark(t: ScanTf | null): string {
    if (!t || t.pctB === null) return '';
    return (t.pctB > 1 ? '▲' : t.pctB < 0 ? '▼' : '') + (t.squeeze ? '◆' : '');
  }

  protected tfTitle(t: ScanTf | null): string {
    if (!t || t.rsi === null) return 'sem barras suficientes';
    const f = (v: number | null, d = 2) => (v === null ? '—' : v.toLocaleString('pt-BR', { maximumFractionDigits: d }));
    return `${t.tf} · RSI ${f(t.rsi, 0)} (agora ${f(t.rsiNow, 0)}) · %B ${f(t.pctB)}` + (t.squeeze ? ' · bandas comprimidas' : '')
      + ` · volume ${f(t.volRatio, 1)}× · sequência ${t.streak}`;
  }

  protected counts(s: ScanSymbol): Record<number, number> {
    const c: Record<number, number> = { 1: 0, 2: 0, 3: 0 };
    for (const a of s.alerts) c[a.severity]++;
    return c;
  }

  protected nextEvent(s: ScanSymbol): CalendarItem | null {
    const now = this.now();
    return this.events().find((e) => s.currencies.includes(e.currency) && new Date(e.scheduled_at).getTime() >= now) ?? null;
  }

  /** A previsão do modelo principal (o da regra do mercado, ou Eventos + Preço a 60 min) do símbolo. */
  protected prediction(symbol: string): Prediction | null {
    const l = this.latest();
    if (!l) return null;
    const model = l.rule?.model ?? 'B', horizon = l.rule?.horizon ?? 60;
    return l.predictions.find((p) => p.symbol === symbol && p.model === model && p.horizonMin === horizon) ?? null;
  }

  protected predTitle(p: Prediction): string {
    const pc = (v: number) => Math.round(v * 100) + '%';
    return `Modelo ${p.model}, ${p.horizonMin} min, momento ${p.momentUtc.slice(11, 16)} UTC: queda ${pc(p.pDown)} · lateral ${pc(p.pFlat)} · alta ${pc(p.pUp)}`
      + (p.signal === 'NO_TRADE' ? ' · não é sinal' : ` · SINAL ${p.signal === 'BUY' ? 'DE COMPRA' : 'DE VENDA'}`);
  }

  protected surprise(e: CalendarItem): number {
    return e.actual === null || e.forecast === null ? 0 : e.actual - e.forecast;
  }

  protected isPast(e: CalendarItem): boolean {
    return new Date(e.scheduled_at).getTime() < this.now();
  }

  protected minutesTo(iso: string): number {
    return (new Date(iso).getTime() - this.now()) / 60_000;
  }

  protected ageMin(iso: string | null): number {
    return iso ? (this.now() - new Date(iso).getTime()) / 60_000 - 1 : 0;   // a barra fecha 1 min depois da abertura
  }

  protected ageText(iso: string | null): string {
    const m = Math.round(this.ageMin(iso));
    return m < 120 ? `${m} min` : m < 48 * 60 ? `${Math.round(m / 60)} h` : `${Math.round(m / 1440)} dias`;
  }

  /** "há 12 min", "em 35 min", "há 3 h"… */
  protected relative(iso: string | null): string {
    if (!iso) return '';
    const m = Math.round((new Date(iso).getTime() - this.now()) / 60_000);
    const a = Math.abs(m);
    const t = a < 90 ? `${a} min` : a < 48 * 60 ? `${Math.round(a / 60)} h` : `${Math.round(a / 1440)} dias`;
    return m > 0 ? `em ${t}` : m === 0 ? 'agora' : `há ${t}`;
  }

  private distance(iso: string | null, now: number): number {
    return iso ? Math.abs(new Date(iso).getTime() - now) : Number.MAX_SAFE_INTEGER;
  }

  /** Preço com casas de acordo com a grandeza (EURUSD 1,16234 · USDJPY 158,431 · US500 6.712,50). */
  protected px(v: number | null | undefined): string {
    if (v === null || v === undefined) return '—';
    const d = v >= 1000 ? 2 : v >= 100 ? 3 : v >= 10 ? 4 : 5;
    return v.toLocaleString('pt-BR', { minimumFractionDigits: d, maximumFractionDigits: d });
  }
}
