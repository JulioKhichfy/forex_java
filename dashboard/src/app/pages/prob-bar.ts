import { Component, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';

/** P(queda) · P(lateral) · P(alta) numa barra só, com os números embaixo. */
@Component({
  selector: 'prob-bar',
  imports: [DecimalPipe],
  template: `
    <div class="bar" [title]="'queda ' + (down() * 100 | number: '1.0-0') + '% · lateral ' + (flat() * 100 | number: '1.0-0') + '% · alta ' + (up() * 100 | number: '1.0-0') + '%'">
      <span class="d" [style.width.%]="down() * 100"></span>
      <span class="f" [style.width.%]="flat() * 100"></span>
      <span class="u" [style.width.%]="up() * 100"></span>
    </div>
    <div class="nums mono">
      <span class="down">{{ down() * 100 | number: '1.0-0' }}</span>
      <span class="muted">{{ flat() * 100 | number: '1.0-0' }}</span>
      <span class="up">{{ up() * 100 | number: '1.0-0' }}</span>
    </div>
  `,
  styles: `
    :host { display: block; min-width: 110px; }
    .bar { display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--panel-2); }
    .d { background: var(--down); }
    .f { background: var(--flat); opacity: .5; }
    .u { background: var(--up); }
    .nums { display: flex; justify-content: space-between; font-size: 11px; margin-top: 2px; }
  `,
})
export class ProbBar {
  down = input.required<number>();
  flat = input.required<number>();
  up = input.required<number>();
}
