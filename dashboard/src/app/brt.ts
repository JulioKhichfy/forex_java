import { Directive, computed, input } from '@angular/core';

const FMT = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo', day: '2-digit', month: '2-digit', year: 'numeric',
  hour: '2-digit', minute: '2-digit', second: '2-digit', timeZoneName: 'shortOffset',
});

/** Data/hora de Brasília para um instante (ISO, Date ou epoch); null se vazio ou inválido. */
export function brasilia(v: string | number | Date | null | undefined): string | null {
  if (v === null || v === undefined || v === '') return null;
  const d = v instanceof Date ? v : new Date(v);
  return isNaN(d.getTime()) ? null : FMT.format(d).replace(',', '');
}

/**
 * Datas do dashboard ficam em UTC (como todo o sistema); ao passar o mouse aparece o horário de Brasília.
 * Uso: <td [brt]="p.momentUtc">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }}</td>
 */
@Directive({
  selector: '[brt]',
  host: { '[attr.title]': 'title()', '[class.brt]': 'title() !== null' },
})
export class Brt {
  brt = input<string | number | Date | null | undefined>(null);
  protected title = computed(() => {
    const s = brasilia(this.brt());
    return s === null ? null : `Brasília: ${s}`;
  });
}
