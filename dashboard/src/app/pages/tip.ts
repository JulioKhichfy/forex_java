import { Component, computed, input } from '@angular/core';
import { HELP } from '../help';

/** "?" que mostra a explicação ao passar o mouse (ou ao focar com o teclado). Use key (glossário) ou text. */
@Component({
  selector: 'tip',
  template: `<span class="tip" tabindex="0" [attr.aria-label]="content()">?<span class="bubble">{{ content() }}</span></span>`,
  styles: `
    :host { display: inline-block; vertical-align: middle; }
    .tip { position: relative; display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px;
      margin-left: 4px; border-radius: 50%; font-size: 10px; font-weight: 700; cursor: help; color: var(--muted);
      border: 1px solid var(--border); background: var(--panel-2); text-transform: none; letter-spacing: 0; }
    .bubble { display: none; position: absolute; z-index: 50; left: 50%; top: 20px; transform: translateX(-50%);
      width: max-content; max-width: 300px; padding: 8px 10px; border-radius: 8px; background: var(--text); color: var(--bg);
      font-size: 12px; font-weight: 400; line-height: 1.4; text-align: left; white-space: normal; box-shadow: 0 4px 14px rgb(0 0 0 / .25); }
    .tip:hover .bubble, .tip:focus .bubble { display: block; }
  `,
})
export class Tip {
  key = input<string>('');
  text = input<string>('');
  protected content = computed(() => this.text() || HELP[this.key()] || this.key());
}
