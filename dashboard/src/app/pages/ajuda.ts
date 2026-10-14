import { Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { GLOSSARIO_NOMES, SECOES, Secao } from '../ajuda-conteudo';
import { HELP } from '../help';

/** Texto puro de uma seção, para a busca. */
function textoDe(s: Secao): string {
  const partes = [s.titulo, s.resumo];
  for (const b of s.blocos) {
    partes.push(b.titulo ?? '', b.html ?? '', b.imagem?.legenda ?? '');
    for (const l of b.tabela?.linhas ?? []) partes.push(l.termo, l.texto);
  }
  return normalizar(partes.join(' ').replace(/<[^>]+>/g, ' '));
}

function normalizar(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

/** Aba Ajuda: o que cada página, tabela e coluna significa, com capturas do próprio sistema. */
@Component({
  selector: 'page-ajuda',
  imports: [FormsModule],
  template: `
    <div class="layout">
      <aside class="toc panel">
        <input class="busca" type="search" placeholder="Buscar na ajuda…" [ngModel]="busca()" (ngModelChange)="busca.set($event)" />
        <nav>
          @for (s of visiveis(); track s.id) {
            <a (click)="ir(s.id)">{{ s.titulo }}</a>
          }
          @if (glossario().length) { <a (click)="ir('glossario')">Glossário</a> }
        </nav>
        @if (busca()) {
          <p class="small muted">{{ visiveis().length }} seção(ões) e {{ glossario().length }} termo(s) com “{{ busca() }}”.</p>
        }
      </aside>

      <div class="conteudo">
        <h1>Ajuda</h1>
        <p class="muted">Tudo o que cada página, tabela e coluna do Jev Forex significa. As imagens são capturas do seu
          próprio sistema (clique para ver em tamanho real). Use a busca à esquerda para achar um termo.</p>

        @for (s of visiveis(); track s.id) {
          <section class="panel secao" [id]="'ajuda-' + s.id">
            <h2>{{ s.titulo }}</h2>
            <p class="resumo">{{ s.resumo }}</p>
            @for (b of s.blocos; track $index) {
              <div class="bloco">
                @if (b.titulo) { <h3>{{ b.titulo }}</h3> }
                @if (b.imagem; as im) {
                  <figure>
                    @if (!faltando()[im.src]) {
                      <a [href]="'ajuda/' + im.src + '.png'" target="_blank" rel="noopener">
                        <img [src]="'ajuda/' + im.src + '.png'" [alt]="im.legenda" loading="lazy" (error)="semImagem(im.src)" />
                      </a>
                    } @else {
                      <div class="sem-imagem">Imagem ainda não gerada. Com o servidor no ar, rode
                        <span class="mono">node tools/capturar-ajuda.mjs</span> na pasta jev-forex.</div>
                    }
                    <figcaption>{{ im.legenda }}</figcaption>
                  </figure>
                }
                @if (b.html) { <div class="texto" [innerHTML]="b.html"></div> }
                @if (b.tabela; as t) {
                  <div class="table-wrap"><table>
                    <tr><th class="termo">{{ t.colunas[0] }}</th><th>{{ t.colunas[1] }}</th></tr>
                    @for (l of t.linhas; track l.termo) {
                      <tr><td class="termo"><b>{{ l.termo }}</b></td><td [innerHTML]="l.texto"></td></tr>
                    }
                  </table></div>
                }
              </div>
            }
          </section>
        }

        @if (glossario().length) {
          <section class="panel secao" id="ajuda-glossario">
            <h2>Glossário</h2>
            <p class="resumo">As mesmas explicações do “?” espalhado pelo dashboard, em ordem alfabética.</p>
            <dl>
              @for (g of glossario(); track g.chave) {
                <dt>{{ g.nome }}</dt><dd>{{ g.texto }}</dd>
              }
            </dl>
          </section>
        }

        @if (!visiveis().length && !glossario().length) {
          <p class="muted">Nada encontrado para “{{ busca() }}”.</p>
        }
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 240px 1fr; gap: 16px; align-items: start; }
    .toc { position: sticky; top: 12px; max-height: calc(100vh - 24px); overflow: auto; }
    .toc nav { display: grid; gap: 2px; margin-top: 10px; }
    .toc a { cursor: pointer; padding: 5px 8px; border-radius: 6px; color: var(--text); text-decoration: none; }
    .toc a:hover { background: var(--panel-2); }
    .busca { width: 100%; box-sizing: border-box; font: inherit; padding: 6px 8px; border-radius: 8px;
      border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .conteudo { min-width: 0; }
    .secao { margin-bottom: 16px; scroll-margin-top: 12px; }
    .secao h2 { margin: 0 0 4px; font-size: 20px; }
    .resumo { color: var(--muted); margin: 0 0 12px; }
    .bloco { margin: 14px 0; }
    .bloco h3 { margin: 0 0 6px; font-size: 15px; text-transform: none; letter-spacing: 0; }
    .texto { line-height: 1.55; }
    .texto :is(ol, ul) { margin: 6px 0; padding-left: 22px; }
    .texto li { margin: 4px 0; }
    figure { margin: 8px 0 12px; }
    figure img { display: block; max-width: 100%; border: 1px solid var(--border); border-radius: 8px;
      box-shadow: 0 2px 10px rgb(0 0 0 / .08); }
    figcaption { font-size: 12px; color: var(--muted); margin-top: 6px; }
    .sem-imagem { padding: 18px; border: 1px dashed var(--border); border-radius: 8px; color: var(--muted); }
    td.termo, th.termo { width: 30%; min-width: 160px; vertical-align: top; }
    td { vertical-align: top; line-height: 1.5; }
    dl { display: grid; grid-template-columns: minmax(160px, 240px) 1fr; gap: 8px 16px; margin: 0; }
    dt { font-weight: 600; }
    dd { margin: 0; }
    @media (max-width: 860px) {
      .layout { grid-template-columns: 1fr; }
      .toc { position: static; max-height: none; }
      dl { grid-template-columns: 1fr; }
      dd { margin-bottom: 8px; }
    }
  `,
})
export class Ajuda {
  protected busca = signal('');
  protected faltando = signal<Record<string, boolean>>({});
  private textos = SECOES.map((s) => ({ s, texto: textoDe(s) }));

  protected visiveis = computed(() => {
    const q = normalizar(this.busca().trim());
    return q ? this.textos.filter((x) => x.texto.includes(q)).map((x) => x.s) : SECOES;
  });

  protected glossario = computed(() => {
    const q = normalizar(this.busca().trim());
    return Object.entries(HELP)
      .map(([chave, texto]) => ({ chave, nome: GLOSSARIO_NOMES[chave] ?? chave, texto }))
      .filter((g) => !q || normalizar(g.nome + ' ' + g.texto).includes(q))
      .sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
  });

  protected ir(id: string): void {
    document.getElementById('ajuda-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  protected semImagem(src: string): void {
    this.faltando.update((m) => ({ ...m, [src]: true }));
  }
}
