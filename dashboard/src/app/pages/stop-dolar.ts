import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { Tip } from './tip';
import { MARKETS } from '../market';

/** trading.risk.money (página "Stop em US$"). */
interface Money {
  enabled: boolean;
  stopUsd: number;
  takeUsd: number;
  trailEnabled: boolean;
  trailStartUsd: number;
  trailDistanceUsd: number;
  trailStepUsd: number;
  timeLockEnabled: boolean;
  timeLockMinProfitUsd: number;
  timeLockKeepPct: number;
  timeLockExtraPct: number;
  timeLockMinutes: number;
  timeLockMaxMinutes: number;
  timeLockStepPct: number;
}

interface PreviewRow {
  market: string;
  symbol: string;
  lot: number;
  stopDistance: number | null;
  takeDistance: number | null;
  stopPoints: number | null;
  spreadPoints: number | null;
  stopsLevel: number | null;
  ok: boolean;
}

@Component({
  selector: 'page-stop-dolar',
  imports: [DecimalPipe, Tip, RouterLink],
  template: `
    @if (embedded()) {
      <h2 class="section">2 · Stop e alvo em US$ (prevalece sobre tudo) e trailing stop</h2>
    }
    <div class="notice" [class.hidden]="embedded()">
      <b>Stop e alvo em dólares, e trailing stop.</b> Ligado, vale <b>por cima</b> do stop e do alvo em ATR da página
      <a routerLink="/risco">Risco</a> em <b>toda ordem nova</b> (manual e da roleta), em todos os mercados. Desligado,
      valem os de ATR. As posições já abertas não mudam de stop/alvo — só o trailing mexe nelas.
    </div>

    @if (draft(); as d) {
      <div class="grid two">
        <section class="panel">
          <h3>Stop e alvo por ordem, em US$</h3>
          <label class="check"><input type="checkbox" [checked]="d.enabled" (change)="set('enabled', $any($event.target).checked)" />
            Usar stop e alvo em dólares (prioridade sobre a página Risco)</label>
          <div class="fields" [class.off]="!d.enabled">
            <label>Perda máxima (stop) <tip text="Quanto você perde, em dólares, se o stop for atingido. Gap e slippage podem piorar um pouco." />
              <span class="money">US$ <input type="number" min="0.1" step="0.5" [value]="d.stopUsd" (input)="set('stopUsd', +$any($event.target).value)" /></span></label>
            <label>Ganho no alvo (take profit) <tip text="Quanto você ganha, em dólares, se o alvo for atingido." />
              <span class="money">US$ <input type="number" min="0.1" step="0.5" [value]="d.takeUsd" (input)="set('takeUsd', +$any($event.target).value)" /></span></label>
          </div>
          <p class="small">
            Alvo = <b>{{ d.takeUsd / d.stopUsd | number: '1.1-1' }}×</b> o stop
            @if (d.takeUsd / d.stopUsd > 0) { (empata acertando {{ 100 / (1 + d.takeUsd / d.stopUsd) | number: '1.0-0' }}% das operações, antes do spread) }
            @if (balance()) { · stop = <b [class.down]="d.stopUsd / balance() > 0.05">{{ d.stopUsd / balance() * 100 | number: '1.1-1' }}%</b> do saldo de US$ {{ balance() | number: '1.2-2' }} }
          </p>
          @if (!d.enabled && atr(); as a) {
            <p class="small muted">Desligado, vale hoje: stop {{ a.stopAtr }} ATR e alvo {{ a.targetR }}× o stop (e os de cada mercado na página Risco).</p>
          }
        </section>

        <section class="panel">
          <h3>Trailing stop <tip text="Move o stop das posições abertas a favor, nunca contra. O EA faz a mudança no servidor da corretora (precisa da versão 2.2 do JevExecutor)." /></h3>
          <label class="check"><input type="checkbox" [checked]="d.trailEnabled" (change)="set('trailEnabled', $any($event.target).checked)" />
            Ativar trailing stop (vale para todas as posições abertas pelo sistema)</label>
          <div class="fields" [class.off]="!d.trailEnabled">
            <label>Começar com lucro de <tip text="Quando o lucro da posição chega a este valor, o stop vai para o preço de entrada: a operação não dá mais prejuízo." />
              <span class="money">US$ <input type="number" min="0.1" step="0.5" [value]="d.trailStartUsd" (input)="set('trailStartUsd', +$any($event.target).value)" /></span></label>
            <label>Distância do stop <tip text="Depois de começar, o stop segue o preço deixando este valor do lucro para trás. Igual ao início = o stop começa exatamente no zero a zero." />
              <span class="money">US$ <input type="number" min="0.1" step="0.5" [value]="d.trailDistanceUsd" (input)="set('trailDistanceUsd', +$any($event.target).value)" /></span></label>
            <label>Mover a cada <tip text="Só pede para mover o stop quando ele trava pelo menos este valor a mais (evita mexer a cada tique)." />
              <span class="money">US$ <input type="number" min="0" step="0.25" [value]="d.trailStepUsd" (input)="set('trailStepUsd', +$any($event.target).value)" /></span></label>
          </div>
          <table class="example small">
            <tr><th>Lucro da posição</th><th>Stop trava</th></tr>
            @for (x of example(); track x.profit) {
              <tr><td>US$ {{ x.profit | number: '1.2-2' }}</td>
                <td>@if (x.lock === null) { <span class="muted">não mexe (stop original)</span> }
                  @else if (x.lock === 0) { <b>zero a zero</b> (stop na entrada) }
                  @else { <span class="up">+US$ {{ x.lock | number: '1.2-2' }}</span> }</td></tr>
            }
          </table>
        </section>
      </div>

      <section class="panel" style="margin-top:16px">
        <h3>Prorrogação com lucro na saída por tempo <tip text="Também liga e desliga pelo botão da página Operar. Precisa do JevExecutor 2.4 (move stop e alvo juntos)." /></h3>
        <label class="check"><input type="checkbox" [checked]="d.timeLockEnabled" (change)="set('timeLockEnabled', $any($event.target).checked)" />
          No fim do tempo, posição com lucro não fecha a mercado: o stop e o alvo acompanham o preço enquanto o lucro sobe</label>
        <div class="grid two">
          <div class="fields" [class.off]="!d.timeLockEnabled">
            <label>Lucro mínimo para prorrogar <tip text="Abaixo disso (ou no prejuízo), a posição fecha por tempo como sempre." />
              <span class="money">US$ <input type="number" min="0.05" step="0.1" [value]="d.timeLockMinProfitUsd" (input)="set('timeLockMinProfitUsd', +$any($event.target).value)" /></span></label>
            <label>O stop garante <tip text="Parte do MAIOR lucro visto na prorrogação que o stop deixa garantida. O resto é o que você aceita devolver para tentar ganhar mais." />
              <span class="money"><input type="number" min="1" max="99" step="5" [value]="d.timeLockKeepPct" (input)="set('timeLockKeepPct', +$any($event.target).value)" /> %</span></label>
            <label>O alvo fica além do preço <tip text="O alvo fica à frente do preço, a esta % do maior lucro; a cada nova máxima ele vai mais à frente." />
              <span class="money"><input type="number" min="1" step="5" [value]="d.timeLockExtraPct" (input)="set('timeLockExtraPct', +$any($event.target).value)" /> %</span></label>
            <label>Nova máxima conta com <tip text="Só move stop e alvo (e renova a janela) quando o lucro passa a máxima anterior em pelo menos esta %. Evita mexer a cada tique." />
              <span class="money">+<input type="number" min="1" step="5" [value]="d.timeLockStepPct" (input)="set('timeLockStepPct', +$any($event.target).value)" /> %</span></label>
            <label>Fecha sem nova máxima em <tip text="A janela renova a cada nova máxima. Se o lucro não fizer nova máxima nesse tempo, fecha a mercado." />
              <span class="money"><input type="number" min="1" step="5" [value]="d.timeLockMinutes" (input)="set('timeLockMinutes', +$any($event.target).value)" /> min</span></label>
            <label>Teto da prorrogação <tip text="Tempo máximo além da saída original, mesmo com o lucro subindo. No teto, fecha a mercado." />
              <span class="money"><input type="number" min="1" step="10" [value]="d.timeLockMaxMinutes" (input)="set('timeLockMaxMinutes', +$any($event.target).value)" /> min</span></label>
          </div>
          <table class="example small">
            <tr><th>Maior lucro até agora</th><th>Stop garante</th><th>Alvo dá</th></tr>
            @for (x of lockExample(); track x.profit) {
              <tr><td>US$ {{ x.profit | number: '1.2-2' }}</td>
                @if (x.keep === null) { <td colspan="2" class="muted">fecha a mercado (lucro abaixo do mínimo)</td> }
                @else { <td class="up">+US$ {{ x.keep | number: '1.2-2' }}</td><td class="up">+US$ {{ x.target | number: '1.2-2' }}</td> }</tr>
            }
          </table>
        </div>
      </section>

      @if (d.enabled) {
        <section class="panel" style="margin-top:16px">
          <h3>Onde fica o stop em cada símbolo
            <span class="small muted">· lote</span>
            <input class="lot" type="number" min="0.01" step="0.01" [value]="lot()" (change)="setLot(+$any($event.target).value)" />
            <tip text="Com o lote escolhido (ou o mínimo do símbolo, se for maior). Stop muito perto do preço = a ordem é recusada pela trava 'Stop em US$ × spread' (precisa ficar além de 2 spreads e do mínimo da corretora)." /></h3>
          @for (m of marketsWithRows(); track m.code) {
            <div class="small muted market">{{ m.label }}</div>
            <div class="table-wrap"><table>
              <tr><th>Símbolo</th><th class="right">Lote</th><th class="right">Stop a</th><th class="right">Alvo a</th><th class="right">Stop em pontos</th><th class="right">Spread agora</th><th></th></tr>
              @for (r of rowsOf(m.code); track r.symbol) {
                <tr>
                  <td class="mono">{{ r.symbol }}</td>
                  <td class="right mono">{{ r.lot | number: '1.2-2' }}</td>
                  <td class="right mono">{{ r.stopDistance === null ? '—' : (r.stopDistance | number: '1.0-5') }}</td>
                  <td class="right mono">{{ r.takeDistance === null ? '—' : (r.takeDistance | number: '1.0-5') }}</td>
                  <td class="right mono">{{ r.stopPoints === null ? '—' : (r.stopPoints | number: '1.0-0') }}</td>
                  <td class="right mono">{{ r.spreadPoints ?? '—' }}</td>
                  <td>@if (r.ok) { <span class="badge ok">ok</span> } @else { <span class="badge high" title="O stop ficaria a menos de 2 spreads (ou do mínimo da corretora): a ordem seria recusada">perto demais</span> }</td>
                </tr>
              }
            </table></div>
          }
        </section>
      }

      <div class="savebar">
        <button class="primary" (click)="save()" [disabled]="!changed() || saving()">{{ saving() ? 'Salvando…' : 'Salvar e aplicar' }}</button>
        @if (changed()) { <button (click)="reset()">Descartar</button> }
        @if (message(); as m) { <span class="small" [class.down]="m.error" [class.up]="!m.error">{{ m.text }}</span> }
        <span class="small muted">Vale na hora, fica gravado em {{ file() }} e no histórico de alterações.</span>
      </div>
    } @else if (outdated()) {
      <p class="down">O servidor em execução é anterior a esta página: reinicie-o (mvn -q package -DskipTests e suba o jar de novo).</p>
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    .check { display: flex; gap: 8px; align-items: center; font-weight: 600; margin-bottom: 10px; }
    .fields { display: grid; gap: 8px; }
    .fields.off { opacity: .5; }
    .fields label { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .money input, input.lot { width: 90px; font: inherit; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); text-align: right; }
    input.lot { width: 70px; margin-left: 6px; text-transform: none; }
    .example { margin-top: 12px; }
    .market { margin: 10px 0 2px; font-weight: 600; text-transform: uppercase; }
    .savebar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 16px; }
    .hidden { display: none; }
    h2.section { margin: 24px 0 10px; }
  `,
})
export class StopDolar implements OnInit {
  /** Dentro da página Risco (sem o aviso do topo, com título de seção). */
  embedded = input(false);
  private http = inject(HttpClient);
  private settings = signal<any>(null);
  protected draft = signal<Money | null>(null);
  protected balance = signal<number>(0);
  protected file = signal<string>('');
  protected saving = signal(false);
  protected message = signal<{ text: string; error: boolean } | null>(null);
  protected lot = signal(0.01);
  protected preview = signal<PreviewRow[]>([]);
  protected outdated = signal(false);

  protected changed = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.settings()?.money));
  protected atr = computed(() => this.settings()?.exits as { stopAtr: number; targetR: number } | undefined);

  /** O que o stop trava em alguns lucros, com os valores da tela. */
  protected example = computed(() => {
    const d = this.draft();
    if (!d) return [];
    const s = d.trailStartUsd, dist = d.trailDistanceUsd;
    return [s * 0.5, s, s + dist / 2, s + dist, s + 2 * dist, s + 4 * dist].map((profit) => ({
      profit, lock: profit < s ? null : Math.max(0, profit - dist),
    }));
  });

  /** Prorrogação com lucro: o que o novo stop garante e o novo alvo dá em alguns lucros. */
  protected lockExample = computed(() => {
    const d = this.draft();
    if (!d) return [];
    const min = d.timeLockMinProfitUsd;
    return [min / 2, min, 1, 2, 5].map((profit) => ({
      profit, keep: profit < min ? null : profit * d.timeLockKeepPct / 100,
      target: profit * (1 + d.timeLockExtraPct / 100),
    }));
  });

  protected marketsWithRows = computed(() => MARKETS.filter((m) => this.preview().some((r) => r.market === m.code)));

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.http.get<any>('/api/risk').subscribe((r) => {
      if (!r.settings?.money) {
        this.outdated.set(true);
        return;
      }
      this.settings.set(r.settings);
      this.draft.set({ ...r.settings.money });
      this.balance.set(r.balance ?? 0);
      this.file.set(r.file);
      this.http.get<any>('/api/trade/state').subscribe({ next: (t) => this.lot.set(t.lot ?? 0.01), error: () => {} });
      this.loadPreview();
    });
  }

  protected rowsOf(market: string): PreviewRow[] {
    return this.preview().filter((r) => r.market === market);
  }

  protected set<K extends keyof Money>(k: K, v: Money[K]): void {
    const d = this.draft();
    if (!d) return;
    this.draft.set({ ...d, [k]: v });
    this.message.set(null);
    if (k === 'stopUsd' || k === 'takeUsd' || k === 'enabled') this.loadPreview();
  }

  protected setLot(v: number): void {
    if (v > 0) {
      this.lot.set(v);
      this.loadPreview();
    }
  }

  private loadPreview(): void {
    const d = this.draft();
    if (!d || !d.enabled || !(d.stopUsd > 0) || !(d.takeUsd > 0)) return;
    this.http.get<PreviewRow[]>(`/api/risk/money-preview?lot=${this.lot()}&stopUsd=${d.stopUsd}&takeUsd=${d.takeUsd}`)
      .subscribe({ next: (p) => this.preview.set(p), error: () => this.preview.set([]) });
  }

  protected reset(): void {
    this.draft.set({ ...this.settings().money });
    this.message.set(null);
    this.loadPreview();
  }

  protected save(): void {
    const d = this.draft();
    if (!d) return;
    const text = (d.enabled ? `Stop US$ ${d.stopUsd} e alvo US$ ${d.takeUsd} em TODA ordem nova.` : 'Stop e alvo em US$ DESLIGADOS (volta o ATR).')
      + '\n' + (d.trailEnabled ? `Trailing: zero a zero com US$ ${d.trailStartUsd} de lucro, depois segue a US$ ${d.trailDistanceUsd}.` : 'Trailing desligado.')
      + '\n' + (d.timeLockEnabled ? `Prorrogação com lucro: stop garante ${d.timeLockKeepPct}%, alvo +${d.timeLockExtraPct}% do maior lucro; fecha sem nova máxima em ${d.timeLockMinutes} min (teto ${d.timeLockMaxMinutes} min).` : 'Prorrogação com lucro desligada.');
    if (!confirm(`Aplicar agora?\n\n${text}`)) return;
    this.saving.set(true);
    // só o bloco em US$: não desfaz o que foi mudado na página Risco enquanto esta estava aberta
    this.http.post<any>('/api/risk/money', d).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.settings.set(r.settings);
        this.draft.set({ ...r.settings.money });
        this.message.set({ text: 'Aplicado.', error: false });
      },
      error: (e) => {
        this.saving.set(false);
        this.message.set({ text: e?.error?.message ?? 'não consegui salvar', error: true });
      },
    });
  }
}
