import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Tip } from './tip';

interface Field {
  key: string;
  label: string;
  kind: 'pct' | 'usd' | 'int' | 'num' | 'lot' | 'min' | 'sec' | 'pts' | 'policy';
  step?: number;
}

const GLOBAL_FIELDS: Field[] = [
  { key: 'maxRiskPerTradePct', label: 'Teto de perda por operação (%)', kind: 'pct', step: 0.5 },
  { key: 'maxRiskPerTradeUsd', label: 'Teto de perda por operação (US$)', kind: 'usd', step: 0.5 },
  { key: 'maxOpenRiskPct', label: 'Risco aberto somado (%)', kind: 'pct', step: 0.5 },
  { key: 'maxDailyLossPct', label: 'Perda máxima no dia (%) — perfil Padrão', kind: 'pct', step: 1 },
  { key: 'maxWeeklyLossPct', label: 'Perda máxima na semana (%) — perfil Padrão', kind: 'pct', step: 1 },
  { key: 'maxPositions', label: 'Posições abertas ao mesmo tempo', kind: 'int', step: 1 },
  { key: 'maxUsdNetExposurePct', label: 'Exposição líquida ao dólar (%)', kind: 'pct', step: 1 },
  { key: 'minLotPolicy', label: 'Política do lote mínimo', kind: 'policy' },
];

const ORDER_FIELDS: Field[] = [
  { key: 'defaultLot', label: 'Lote padrão do tíquete', kind: 'lot', step: 0.01 },
  { key: 'maxLot', label: 'Lote máximo por ordem', kind: 'lot', step: 0.01 },
  { key: 'closeAfterMinutes', label: 'Saída por tempo (min)', kind: 'min', step: 5 },
  { key: 'deviationPoints', label: 'Desvio máximo na execução (pontos)', kind: 'pts', step: 1 },
  { key: 'validitySeconds', label: 'Validade da ordem (s)', kind: 'sec', step: 5 },
  { key: 'quoteMaxAgeSeconds', label: 'Idade máxima da cotação (s)', kind: 'sec', step: 5 },
  { key: 'atrMaxAgeMinutes', label: 'Idade máxima do ATR (min)', kind: 'min', step: 5 },
  { key: 'blackoutBeforeMinutes', label: 'Blackout antes de evento alto (min)', kind: 'min', step: 1 },
  { key: 'blackoutAfterMinutes', label: 'Blackout depois de evento alto (min)', kind: 'min', step: 1 },
  { key: 'maxSpreadRatio', label: 'Spread máximo (× o típico do horário)', kind: 'num', step: 0.25 },
  { key: 'minMarginLevelPct', label: 'Nível de margem mínimo (%)', kind: 'pct', step: 50 },
];

const EXIT_FIELDS: Field[] = [
  { key: 'stopAtr', label: 'Stop (× ATR) = 1 R', kind: 'num', step: 0.25 },
  { key: 'targetR', label: 'Alvo (× stop)', kind: 'num', step: 0.25 },
];

const MARKET_LABELS: Partial<Record<string, string>> = { FX: 'Forex', INDICES: 'Índices', STOCKS: 'Ações', METALS: 'Metais', CRYPTO: 'Cripto' };

@Component({
  selector: 'page-risco',
  imports: [DatePipe, DecimalPipe, FormsModule, Tip],
  template: `
    <h1>Risco</h1>
    <div class="notice">
      Estas são as regras que o sistema confere <b>antes de cada ordem</b>. Ao salvar, a mudança vale <b>na hora</b>
      (sem reiniciar), fica gravada em <span class="mono small">{{ file() }}</span> e registrada no histórico abaixo.
      Valor fora do permitido é recusado. Os valores em US$ usam o saldo atual da conta:
      <b>US$ {{ balance() | number: '1.2-2' }}</b>
      @if (accountMode()) { <span class="badge" [class.warn]="accountMode() === 'REAL'">{{ accountMode() }}</span> }
    </div>

    @if (draft(); as d) {
      <section class="panel">
        <h3>1 · Limites da conta inteira <tip text="Valem somando todos os mercados (forex, índices, ações). Uma ordem só passa se respeitar todos." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Regra</th><th>Valor</th><th>Na prática, com o saldo atual <tip text="O mesmo limite convertido em dinheiro, usando o saldo informado pelo EA." /></th><th>Salvo</th></tr>
          @for (f of globalFields; track f.key) {
            <tr [class.changed]="changed('global', f.key)">
              <td>{{ f.label }} <tip [key]="f.key" /></td>
              <td>
                @if (f.kind === 'policy') {
                  <select [(ngModel)]="d.global[f.key]">
                    <option value="ALLOW_UP_TO_CAP">ALLOW_UP_TO_CAP — opera com 0,01 até o teto</option>
                    <option value="SKIP">SKIP — não opera se 0,01 passa do alvo</option>
                  </select>
                } @else {
                  <input type="number" [(ngModel)]="d.global[f.key]" [step]="f.step ?? 1" min="0" />
                }
              </td>
              <td class="small">{{ globalHint(f.key) }}</td>
              <td class="small muted mono">{{ saved()?.global?.[f.key] }}</td>
            </tr>
          }
        </table></div>
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>2 · Travas de cada ordem enviada ao MT5 <tip text="Conferidas no clique e de novo no envio. O dashboard só escolhe o lote, entre o lote mínimo da corretora e o lote máximo daqui." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Trava</th><th>Valor</th><th>Na prática</th><th>Salvo</th></tr>
          @for (f of orderFields; track f.key) {
            <tr [class.changed]="changed('orders', f.key)">
              <td>{{ f.label }} <tip [key]="f.key" /></td>
              <td><input type="number" [(ngModel)]="d.orders[f.key]" [step]="f.step ?? 1" min="0" /></td>
              <td class="small">{{ orderHint(f.key) }}</td>
              <td class="small muted mono">{{ saved()?.orders?.[f.key] }}</td>
            </tr>
          }
        </table></div>
      </section>

      <div class="grid two" style="margin-top:16px">
        <section class="panel">
          <h3>3 · Saídas <tip text="Toda ordem sai com stop e alvo no servidor da corretora. Mudar aqui muda as ordens novas; os modelos foram avaliados com stop 1,5 ATR e alvo 1,5 × stop." /></h3>
          <table>
            <tr><th>Saída</th><th>Valor</th><th>Na prática</th></tr>
            @for (f of exitFields; track f.key) {
              <tr [class.changed]="changed('exits', f.key)">
                <td>{{ f.label }} <tip [key]="f.key" /></td>
                <td><input type="number" [(ngModel)]="d.exits[f.key]" [step]="f.step ?? 0.25" min="0" /></td>
                <td class="small">{{ exitHint(f.key) }}</td>
              </tr>
            }
          </table>
        </section>

        <section class="panel">
          <h3>5 · Perfis de perda <tip key="lossProfile" /></h3>
          <table>
            <tr><th>Perfil</th><th>No dia (%) <tip key="maxDailyLossPct" /></th><th>Na semana (%) <tip key="maxWeeklyLossPct" /></th></tr>
            @for (p of profileKeys(d); track p) {
              <tr [class.changed]="changed('lossProfiles', p)">
                <td>{{ d.lossProfiles[p].label }}</td>
                @if (p === 'padrao') {
                  <td class="mono muted">{{ d.global.maxDailyLossPct }} <tip text="O perfil Padrão usa os limites da seção 1." /></td>
                  <td class="mono muted">{{ d.global.maxWeeklyLossPct }}</td>
                } @else {
                  <td><input type="number" [(ngModel)]="d.lossProfiles[p].maxDailyLossPct" step="1" min="0" /></td>
                  <td><input type="number" [(ngModel)]="d.lossProfiles[p].maxWeeklyLossPct" step="1" min="0" /></td>
                }
              </tr>
            }
          </table>
        </section>
      </div>

      <section class="panel" style="margin-top:16px">
        <h3>4 · Orçamento por mercado <tip text="Além dos limites da conta inteira, cada mercado tem o próprio orçamento. Vale o mais restritivo." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Mercado</th><th>Ativo <tip key="marketEnabled" /></th><th>Risco-alvo por operação (%) <tip key="riskPerTradePct" /></th>
            <th>Risco aberto (%) <tip key="marketMaxOpenRiskPct" /></th><th>Posições <tip key="marketMaxPositions" /></th>
            <th>Peso no USD <tip key="usdWeight" /></th><th>Saída por tempo (min) <tip key="marketCloseAfter" /></th>
            <th>Stop (ATR) <tip key="marketStopAtr" /></th><th>Alvo (× stop) <tip key="marketTargetR" /></th></tr>
          @for (m of marketKeys(d); track m) {
            <tr [class.changed]="changed('markets', m)">
              <td>{{ marketLabels[m] ?? m }}</td>
              <td><input type="checkbox" [(ngModel)]="d.markets[m].enabled" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].riskPerTradePct" step="0.5" min="0" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].maxOpenRiskPct" step="0.5" min="0" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].maxPositions" step="1" min="0" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].usdWeight" step="0.5" min="0" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].closeAfterMinutes" step="5" min="0" placeholder="padrão" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].stopAtr" step="0.5" min="0" placeholder="padrão" /></td>
              <td><input type="number" [(ngModel)]="d.markets[m].targetR" step="0.5" min="0" placeholder="padrão" /></td>
            </tr>
          }
        </table></div>
      </section>

      <section class="panel savebar">
        @if (diff().length) {
          <div><b>{{ diff().length }} mudança(s):</b>
            @for (c of diff(); track c.path) { <span class="badge warn mono">{{ c.path }}: {{ c.from }} → {{ c.to }}</span> }
          </div>
        } @else {
          <span class="muted">Nenhuma mudança.</span>
        }
        <input class="note" placeholder="Motivo (opcional, vai para o histórico)" [(ngModel)]="note" />
        <button (click)="reset()" [disabled]="!diff().length">Descartar</button>
        <button class="primary" (click)="save()" [disabled]="!diff().length || saving()">{{ saving() ? 'Salvando…' : 'Salvar e aplicar' }}</button>
        @if (message()) { <span class="small" [class.down]="error()" [class.up]="!error()">{{ message() }}</span> }
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Histórico de alterações</h3>
        <div class="table-wrap"><table>
          <tr><th>Quando</th><th>Origem</th><th>Motivo</th><th>O que mudou</th></tr>
          @for (h of history(); track h.id) {
            <tr>
              <td class="small mono nowrap">{{ h.changedAt | date: 'dd/MM/yyyy HH:mm' }}</td>
              <td class="small">{{ h.source }}</td>
              <td class="small">{{ h.note }}</td>
              <td class="small">@for (c of h.changes; track c.path) { <span class="mono">{{ c.path }}: {{ c.from }} → {{ c.to }}</span><br /> }</td>
            </tr>
          } @empty {
            <tr><td colspan="4" class="muted">Nenhuma alteração pelo dashboard ainda (os valores vêm do application.yml).</td></tr>
          }
        </table></div>
      </section>
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    input[type=number], select, .note { font: inherit; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); }
    input[type=number] { width: 110px; }
    tr.changed td { background: var(--warn-bg); }
    .savebar { position: sticky; bottom: 0; margin-top: 16px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
      z-index: 5; box-shadow: 0 -4px 12px rgb(0 0 0 / .08); }
    .savebar .badge { margin: 2px 4px 2px 0; }
    .note { flex: 1; min-width: 200px; }
  `,
})
export class Risco implements OnInit {
  private http = inject(HttpClient);
  protected globalFields = GLOBAL_FIELDS;
  protected orderFields = ORDER_FIELDS;
  protected exitFields = EXIT_FIELDS;
  protected marketLabels = MARKET_LABELS;
  protected saved = signal<any>(null);
  protected draft = signal<any>(null);
  protected balance = signal<number>(0);
  protected accountMode = signal<string | null>(null);
  protected file = signal<string>('');
  protected history = signal<any[]>([]);
  protected saving = signal(false);
  protected message = signal<string | null>(null);
  protected error = signal(false);
  protected note = '';
  private tick = signal(0);   // os inputs mudam o objeto por dentro: este sinal força recalcular o diff
  protected diff = computed(() => { this.tick(); return changes(this.saved(), this.draft()); });

  ngOnInit(): void {
    this.load();
    setInterval(() => this.tick.update((t) => t + 1), 500);
  }

  protected changed(section: string, key: string): boolean {
    this.tick();
    return JSON.stringify(this.saved()?.[section]?.[key]) !== JSON.stringify(this.draft()?.[section]?.[key]);
  }

  protected profileKeys(d: any): string[] {
    return Object.keys(d.lossProfiles ?? {});
  }

  protected marketKeys(d: any): string[] {
    return Object.keys(d.markets ?? {}).sort((a, b) => (a === 'FX' ? -1 : b === 'FX' ? 1 : a.localeCompare(b)));
  }

  protected globalHint(key: string): string {
    const g = this.draft()?.global, b = this.balance();
    if (!g) return '';
    const usd = (pct: number) => `US$ ${(b * pct / 100).toFixed(2)}`;
    switch (key) {
      case 'maxRiskPerTradePct': return `${usd(g.maxRiskPerTradePct)} — vale o menor entre isso e o teto em US$: US$ ${this.cap().toFixed(2)}`;
      case 'maxRiskPerTradeUsd': return g.maxRiskPerTradeUsd > 0 ? `teto por operação hoje: US$ ${this.cap().toFixed(2)}` : 'sem teto em dinheiro (só o %)';
      case 'maxOpenRiskPct': return `todas as posições juntas podem perder até ${usd(g.maxOpenRiskPct)} se derem stop`;
      case 'maxDailyLossPct': return `bloqueia entradas novas depois de perder ${usd(g.maxDailyLossPct)} no dia`;
      case 'maxWeeklyLossPct': return `bloqueia entradas novas depois de perder ${usd(g.maxWeeklyLossPct)} na semana`;
      case 'maxPositions': return `até ${g.maxPositions} posições abertas ao mesmo tempo`;
      case 'maxUsdNetExposurePct': return `exposição líquida a USD até ${usd(g.maxUsdNetExposurePct)}`;
      case 'minLotPolicy': return g.minLotPolicy === 'SKIP' ? 'mais seguro: com banca pequena pode quase não operar'
        : `opera com 0,01 se a perda no stop ficar até US$ ${this.cap().toFixed(2)}`;
    }
    return '';
  }

  protected orderHint(key: string): string {
    const o = this.draft()?.orders, e = this.draft()?.exits;
    if (!o) return '';
    switch (key) {
      case 'defaultLot': return `0,01 lote no EURUSD ≈ US$ 0,10 por pip; ${o.defaultLot} ≈ US$ ${(o.defaultLot * 10).toFixed(2)} por pip`;
      case 'maxLot': return `com stop típico de ~10 pips no EURUSD, ${o.maxLot} lote arrisca ≈ US$ ${(o.maxLot * 10 * 10).toFixed(2)}`;
      case 'closeAfterMinutes': return o.closeAfterMinutes > 0 ? `fecha sozinha depois de ${o.closeAfterMinutes} min` : 'só sai no stop ou no alvo';
      case 'deviationPoints': return `${(o.deviationPoints / 10).toFixed(1)} pip(s) no EURUSD`;
      case 'validitySeconds': return `ordem não executada em ${o.validitySeconds} s é descartada`;
      case 'blackoutBeforeMinutes': return `sem entrada de ${o.blackoutBeforeMinutes} min antes até ${o.blackoutAfterMinutes} min depois de evento alto`;
      case 'blackoutAfterMinutes': return 'o sistema decide 2 min depois do evento: menos que 2 aqui libera essa entrada';
      case 'maxSpreadRatio': return `spread típico de 8 pontos → aceita até ${(8 * o.maxSpreadRatio).toFixed(0)}`;
      case 'minMarginLevelPct': return `a Exness costuma fechar posições com nível muito baixo; ${o.minMarginLevelPct}% é folga`;
    }
    return e ? '' : '';
  }

  protected exitHint(key: string): string {
    const e = this.draft()?.exits;
    if (!e) return '';
    return key === 'stopAtr' ? `ATR de 8 pips (EURUSD típico) → stop de ${(8 * e.stopAtr).toFixed(0)} pips`
      : `ganha ${e.targetR} R no alvo; empata acertando ${(100 / (1 + e.targetR)).toFixed(0)}% (antes do custo)`;
  }

  private cap(): number {
    const g = this.draft()?.global, b = this.balance();
    const byPct = b * g.maxRiskPerTradePct / 100;
    return g.maxRiskPerTradeUsd > 0 ? Math.min(byPct, g.maxRiskPerTradeUsd) : byPct;
  }

  protected reset(): void {
    this.draft.set(structuredClone(this.saved()));
    this.message.set(null);
  }

  protected save(): void {
    const list = this.diff().map((c) => `• ${c.path}: ${c.from} → ${c.to}`).join('\n');
    if (!confirm(`Aplicar agora estas mudanças de risco?\n\n${list}`)) return;
    this.saving.set(true);
    this.http.post<any>('/api/risk', { settings: this.draft(), note: this.note || null }).subscribe({
      next: (r) => {
        this.saved.set(r.settings);
        this.draft.set(structuredClone(r.settings));
        this.saving.set(false);
        this.error.set(false);
        this.message.set('Salvo e em vigor.');
        this.note = '';
        this.loadHistory();
      },
      error: (e) => {
        this.saving.set(false);
        this.error.set(true);
        this.message.set('Recusado: ' + (e?.error?.message ?? 'erro'));
      },
    });
  }

  private load(): void {
    this.http.get<any>('/api/risk').subscribe((r) => {
      this.saved.set(r.settings);
      this.draft.set(structuredClone(r.settings));
      this.balance.set(r.balance ?? 0);
      this.accountMode.set(r.account_mode ?? null);
      this.file.set(r.file);
    });
    this.loadHistory();
  }

  private loadHistory(): void {
    this.http.get<any[]>('/api/risk/history').subscribe((h) =>
      this.history.set(h.map((x) => ({ ...x, changes: changes(JSON.parse(x.oldJson), JSON.parse(x.newJson)) }))));
  }
}

/** Diferenças campo a campo entre duas configurações (caminho, antes, depois). */
function changes(a: any, b: any, prefix = ''): { path: string; from: any; to: any }[] {
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return JSON.stringify(a) === JSON.stringify(b) ? [] : [{ path: prefix, from: a, to: b }];
  }
  const out: { path: string; from: any; to: any }[] = [];
  for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
    out.push(...changes(a[k], b[k], prefix ? `${prefix}.${k}` : k));
  }
  return out;
}
