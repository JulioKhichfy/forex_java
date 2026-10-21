/**
 * Conteúdo da aba Ajuda. Texto com HTML simples (<b>, <i>, <code>, <br>). As imagens são capturas do próprio
 * sistema em ajuda/*.png, geradas por tools/capturar-ajuda.mjs (ficam fora do git: mostram a sua conta).
 * Mudou uma página do dashboard? Atualize a seção dela aqui e rode o script de novo.
 */

export interface Linha {
  termo: string;
  texto: string;
}

export interface Bloco {
  titulo?: string;
  html?: string;
  tabela?: { colunas: [string, string]; linhas: Linha[] };
  imagem?: { src: string; legenda: string };
}

export interface Secao {
  id: string;
  titulo: string;
  resumo: string;
  blocos: Bloco[];
}

const ALTA = '<span class="up">ALTA</span>';
const QUEDA = '<span class="down">QUEDA</span>';

export const SECOES: Secao[] = [
  // ======================================================================== visão geral
  {
    id: 'visao-geral',
    titulo: 'Visão geral',
    resumo: 'O que o sistema faz, de onde vêm os dados e como ler qualquer tela.',
    blocos: [
      {
        html: `O Jev Forex é uma <b>ferramenta de apoio à decisão</b>. Em momentos fixos ele calcula a probabilidade de o
          preço subir (${ALTA}), ficar parado (LATERAL) ou cair (${QUEDA}) nos próximos 15 e 60 minutos. <b>Quem decide é
          você</b>: o sistema mostra a previsão, você clica, ele confere todas as travas de risco e o EA no MetaTrader 5
          executa a ordem com stop e alvo gravados no servidor da corretora.`,
      },
      {
        titulo: 'Princípios que valem em todas as telas',
        tabela: {
          colunas: ['Princípio', 'O que significa na prática'],
          linhas: [
            { termo: 'O Jev interpreta, o código decide', texto: 'O Jev (modelo de linguagem da TypeSafe) só lê textos dos bancos centrais e devolve números (tom, relevância). Comprar ou vender nunca é decisão dele.' },
            { termo: 'Point-in-time', texto: 'Toda previsão usa só o que já era conhecido naquele instante. O mesmo código calcula as variáveis no treino e ao vivo.' },
            { termo: 'Falhar fechado', texto: 'Dado velho, spread alto, EA desconectado, dúvida: o sistema NÃO opera e mostra o motivo. Uma ordem recusada é o comportamento esperado, não um defeito.' },
            { termo: 'Risco por mercado e global', texto: 'Cada mercado tem o próprio orçamento e a conta inteira tem limites somados. Vale sempre o mais restritivo.' },
            { termo: 'Tudo em UTC', texto: 'Datas e horas aparecem em UTC (o horário do mercado). <b>Passe o mouse sobre qualquer data sublinhada</b> para ver o horário de Brasília (UTC−3, sem horário de verão). Ex.: 12:30 UTC = 09:30 em Brasília.' },
            { termo: 'O “?” ao lado de um título', texto: 'Passe o mouse (ou toque) no “?” para ver a explicação curta daquela coluna ou conceito. O glossário no fim desta página reúne todas.' },
          ],
        },
      },
      {
        titulo: 'O caminho do dado, do MetaTrader até o seu clique',
        html: `<ol class="flow">
          <li><b>MetaTrader 5</b> — dois serviços (JevCandleExporter e JevCalendarExporter) gravam as barras de 1 minuto e o
            calendário econômico em arquivos na pasta comum do MT5 (<code>Common\\Files\\jev\\inbox</code>).</li>
          <li><b>Importador</b> (dentro do servidor, a cada 2 s) — lê os arquivos, guarda o original intocado no lake
            (camada <i>bronze</i>) e no Postgres.</li>
          <li><b>Normalize</b> (automático às 21:30 UTC, seg a sex) — organiza o bronze em tabelas limpas (<i>silver</i>).</li>
          <li><b>Features</b> — transforma preço e calendário nas variáveis que os modelos usam (volatilidade, retornos,
            surpresa dos indicadores…), camada <i>gold</i>.</li>
          <li><b>Modelos A, E e B</b> — em cada momento de decisão, calculam as probabilidades de ALTA, LATERAL e QUEDA.</li>
          <li><b>Dashboard</b> — mostra previsões, sinais, eventos, notícias e o placar.</li>
          <li><b>Sua ordem</b> — o servidor confere as travas (página Risco) e, se tudo passar, deixa a ordem para o EA.</li>
          <li><b>EA JevExecutor</b> (no gráfico do MT5) — busca a ordem, executa com SL e TP no servidor da corretora e
            avisa o resultado. Também manda a cada poucos segundos o saldo, as cotações e as posições (heartbeat).</li>
        </ol>`,
      },
      {
        titulo: 'A barra do topo',
        imagem: { src: 'topo', legenda: 'Menu das páginas, escolha do mercado, modo do sistema e relógio do servidor.' },
        tabela: {
          colunas: ['Elemento', 'O que faz'],
          linhas: [
            { termo: 'Hoje · Radar · Previsões · Operar · Roleta russa · Risco · Laboratório · Modelos · Saúde · Ajuda', texto: 'As páginas. Cada uma tem uma seção nesta ajuda.' },
            { termo: 'Forex · Índices · Ações · Ouro · Bitcoin · Energia', texto: 'O mercado que as páginas Hoje, Radar, Previsões, Operar e Modelos mostram. A escolha fica guardada no navegador. Forex = 7 pares principais; Índices = US30, US500, USTEC, DE30, UK100, JP225 e outros; Ações = AAPL, MSFT, NVDA, META, GOOGL, INTC, SPCX; Ouro = XAUUSD; Bitcoin = BTCUSD; Energia = UKOIL (Brent), USOIL (WTI) e XNGUSD (gás natural).' },
            { termo: 'modo SHADOW / DEMO / LIVE', texto: '<b>SHADOW</b>: as ordens são conferidas e registradas, mas não vão para o MT5. <b>DEMO</b>: vão só para conta demo. <b>LIVE</b>: conta real (selo em laranja). O modo é escolhido ao subir o servidor (<code>--trading.mode=demo</code>).' },
            { termo: 'Relógio (UTC)', texto: 'Hora do servidor em UTC; com o mouse, Brasília. Se aparecer “API fora do ar”, o servidor (java -jar jev-app.jar) não está respondendo.' },
          ],
        },
      },
      {
        titulo: 'As três chaves para uma ordem chegar à corretora',
        html: `Por segurança, o EA só envia ordens com <b>as três chaves ligadas</b>: (1) no EA, o parâmetro
          <code>InpExecute = true</code>; (2) no servidor, <code>trading.mode</code> DEMO ou LIVE; (3) o modo combinando com a
          conta (DEMO só em conta demo, LIVE só em conta real). Faltou uma? A ordem fica registrada, mas nada é executado.`,
      },
    ],
  },

  // ======================================================================== hoje
  {
    id: 'hoje',
    titulo: 'Página Hoje',
    resumo: 'O resumo do momento: sinais, a previsão mais provável de cada símbolo, eventos e notícias. É daqui que se opera pela previsão.',
    blocos: [
      { imagem: { src: 'hoje-ouro', legenda: 'Hoje com o mercado Ouro: a regra própria do ouro aparece em destaque, e eventos e notícias mostram só USD.' } },
      {
        titulo: 'Sinal automático (ouro)',
        html: `No mercado Ouro, o quadro <b>Sinal automático</b> liga e desliga o envio sozinho: quando a regra do ouro dá
          sinal (B a 15 min, só depois de divulgação), a ordem sai no lado do sinal com o <b>lote escolhido</b>, em segundos
          — no histórico, entrar 5 min atrasado levava o resultado para perto de zero. <b>Todas as travas de risco
          valem</b> (teto e alvo de risco, posições, um por símbolo, margem, spread, blackout, sessão, perda do dia e da
          semana): o que elas recusarem aparece na tabela com o motivo. Sinal com mais de 180 s não vira ordem
          (aparece como <b>atrasado</b>). Na conta real, ligar pede digitar LIVE. A tabela mostra cada sinal visto:
          enviada, recusada ou atrasado, e o resultado depois que a posição fecha. Só os sinais depois de ligar contam.
          Hoje só o ouro pode ser automático (<code>live.auto-markets</code>), a regra com mais evidência.`,
      },
      {
        titulo: 'Modo só sinais (aviso em qualquer página)',
        html: `Os mercados com <b>regra de sinal</b> — ouro (Eventos + Preço, 15 min) e índices (Eventos + Preço, 60 min),
          as duas regras que se sustentaram fora da amostra e no Laboratório — são vigiados o tempo todo, em qualquer página
          do dashboard. Quando sai um sinal: uma <b>faixa verde (compra) ou vermelha (venda)</b> no topo, um bipe e uma
          notificação do Windows (permita as notificações no navegador). <b>Ver e operar</b> abre a página Hoje do mercado,
          com o botão <b>Operar agora</b> (todas as travas valem). O sinal vale por poucos minutos: a faixa some depois de
          10 min. Os dois disparam poucas vezes por mês, só depois de divulgações. É a alternativa recomendada à roleta,
          que entra em toda previsão e teve E[R] negativo em quase todos os mercados. Precisa do dashboard aberto.`,
      },
      {
        titulo: 'Aviso amarelo do topo',
        html: `Lembra que a previsão é apoio, não garantia. Nos testes fora da amostra, só o ouro mostrou vantagem
          depois dos custos; os demais mercados não. Confira o placar (Previsões) e o cofre (Modelos) antes de confiar.`,
      },
      {
        titulo: 'Aviso de SINAL (só aparece quando há sinal novo)',
        html: `Quando uma previsão passa na regra do mercado (no ouro: a regra própria; nos demais: o gate 4) e tem
          <b>até 10 minutos</b>, aparece um quadro em destaque com o lado (COMPRA em verde, VENDA em vermelho), o símbolo, o
          modelo, o horizonte, o horário do momento e as probabilidades. O navegador toca um bipe e mostra uma notificação
          (permita as notificações na primeira vez). O botão <b>Operar agora</b> abre o quadro de confirmação com o lote
          escolhido. <br><b>Por que a pressa:</b> no histórico do ouro, entrar 5 minutos depois do sinal levava o resultado
          médio para perto de zero. A ordem do ouro sai sozinha em 15 minutos.`,
      },
      {
        titulo: 'Sinais agora',
        html: `Lista só as previsões que passaram no critério do mercado no último momento de decisão. O título diz qual
          critério vale: <b>gate 4</b> (P ≥ 60% e diferença ≥ 35 pontos para o lado oposto — raro de propósito) ou
          <b>regra do mercado</b> (no ouro: modelo Eventos + Preço, 15 min, P ≥ 45%, diferença ≥ 15 pontos, só no momento
          de uma divulgação). A faixa amarela descreve a regra; o “?” ao lado mostra de onde ela veio (walk-forward e cofre).`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Par', texto: 'O símbolo (nome canônico, sem o sufixo “m” da Exness).' },
            { termo: 'Modelo', texto: 'Preço (A), Eventos (E) ou Eventos + Preço (B). Veja “Modelos e sinais”.' },
            { termo: 'Horizonte', texto: 'Em quantos minutos a previsão se resolve (15 ou 60).' },
            { termo: 'Sinal', texto: 'COMPRA ou VENDA.' },
            { termo: 'Probabilidades', texto: 'Barra em três cores: vermelho = QUEDA, cinza = LATERAL, verde = ALTA. Os números embaixo são as porcentagens.' },
          ],
        },
      },
      {
        titulo: 'Mais provável por símbolo',
        html: `Uma linha por símbolo do mercado com a previsão do modelo principal: <b>Eventos + Preço a 60 min</b>, ou o
          modelo e horizonte da regra do mercado (no ouro, <b>15 min</b>). Daqui você opera um símbolo ou todos de uma vez.`,
        tabela: {
          colunas: ['Coluna / botão', 'Significado'],
          linhas: [
            { termo: 'Lote por ordem', texto: 'O tamanho de cada ordem (0,01 = o mínimo). Limitado pelo lote máximo da página Risco.' },
            { termo: 'Operar todos (N)', texto: 'Calcula uma ordem por símbolo da tabela, cada uma no lado mais provável, e abre o quadro de confirmação. Nada é enviado ainda.' },
            { termo: 'Símbolo', texto: 'O ativo.' },
            { termo: 'Momento', texto: 'O instante de decisão a que a previsão se refere. “· evento” = 2 minutos depois da divulgação de um indicador.' },
            { termo: 'Probabilidades (queda · lateral · alta)', texto: 'A mesma barra de três cores.' },
            { termo: 'Direção', texto: 'COMPRA se P(alta) > P(queda), senão VENDA. A <b>força</b> é a diferença entre as duas, em pontos. O selo <b>fraca</b> diz por que ela não é sinal (lateral mais provável, ou não passou no gate 4 / na regra).' },
            { termo: 'Operar', texto: 'Calcula a ordem só deste símbolo e abre a confirmação. Depois do envio, o resultado aparece ao lado (ordem #n enviada, ou recusada e por quê).' },
          ],
        },
      },
      {
        titulo: 'Quadro “Confirmar as ordens”',
        tabela: {
          colunas: ['Coluna / botão', 'Significado'],
          linhas: [
            { termo: 'Símbolo / Lado', texto: 'O que será enviado.' },
            { termo: 'Perda máx.', texto: 'Quanto você perde, em US$, se o stop for atingido (1 R), com o lote escolhido. Gap e slippage podem piorar.' },
            { termo: 'Ganho no alvo', texto: 'Quanto ganha se o alvo for atingido.' },
            { termo: 'Travas', texto: '“todas ok” ou “bloqueada:” seguido das travas que falharam (ver Operar → Travas). Linhas bloqueadas ficam apagadas e não são enviadas.' },
            { termo: 'Enviar N', texto: 'Pede confirmação e envia uma a uma só as que passaram. Cada envio confere as travas de novo: depois da 1ª, o limite de posições pode recusar as seguintes.' },
            { termo: 'Cancelar', texto: 'Fecha o quadro sem enviar nada.' },
          ],
        },
      },
      {
        titulo: 'Próximos eventos (24 h)',
        html: `Indicadores econômicos de importância média ou alta, das últimas 2 horas às próximas 24. No Ouro e no
          Bitcoin aparecem só os eventos em <b>USD</b> (os dois são cotados em dólar). Eventos que já passaram ficam em cinza.`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Quando (UTC)', texto: 'Horário programado da divulgação (mouse = Brasília).' },
            { termo: 'Moeda', texto: 'A moeda do país. Em vermelho = importância ALTA (é o que dispara o blackout das ordens).' },
            { termo: 'Evento', texto: 'Nome do indicador (ex.: Nonfarm Payrolls, CPI, decisão de juros).' },
            { termo: 'Previsto', texto: 'O consenso do mercado. O preço reage à SURPRESA: atual − previsto.' },
            { termo: 'Atual', texto: 'O valor divulgado; aparece segundos depois do horário.' },
          ],
        },
      },
      {
        titulo: 'Últimas notícias dos bancos centrais',
        html: `Os documentos mais recentes coletados dos sites do Fed, BCE, BoE, BoJ, SNB, BoC e RBNZ (a coleta roda a cada
          minuto). No Ouro e no Bitcoin, só o Fed (USD). Os botões <b>Jev: ver plano e custo</b> (não gasta nada: mostra
          quantos trechos faltam e o custo estimado) e <b>Jev: avaliar (até US$ 0,10)</b> (chama o Jev com teto de gasto)
          produzem a leitura do tom.`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Visto em (UTC)', texto: 'Quando o coletor encontrou o documento pela primeira vez.' },
            { termo: 'Emissor', texto: 'O banco central e a moeda dele.' },
            { termo: 'Título', texto: 'Link para o documento original.' },
            { termo: 'Leitura do Jev', texto: '<b>hawkish</b> (verde: sinaliza juros mais altos, tende a fortalecer a moeda), <b>dovish</b> (vermelho: juros mais baixos) ou <b>neutro</b>, com o número de −1 a +1. <b>relev.</b> = relevância para a política monetária. “não avaliado” = o Jev ainda não leu.' },
          ],
        },
      },
      {
        titulo: 'Atualização',
        html: 'As previsões são recarregadas a cada 20 segundos; eventos e notícias, a cada minuto.',
      },
      { imagem: { src: 'hoje-forex', legenda: 'Hoje com o mercado Forex: sem regra própria, vale o gate 4 e a tabela mostra Eventos + Preço a 60 min.' } },
    ],
  },

  // ======================================================================== radar
  {
    id: 'radar',
    titulo: 'Página Radar',
    resumo: 'O que está fora do normal agora em cada símbolo: movimentos grandes, volume, RSI, bandas de Bollinger, rompimentos, gaps, spread, eventos e notícias.',
    blocos: [
      { imagem: { src: 'radar', legenda: 'Radar do forex: alertas, próximos eventos, mapa por símbolo e força das moedas.' } },
      {
        titulo: 'Para que serve (e para que não serve)',
        html: `O Radar <b>descreve</b> o que o preço está fazendo, em vários períodos ao mesmo tempo (M5, M15, H1, H4 e D1),
          e junta os eventos e as notícias das moedas de cada símbolo. <b>Nenhum alerta é sinal de entrada</b>: nenhum deles
          foi testado como estratégia com resultado positivo — a volta às bandas de Bollinger, testada em 13/10, deu resultado
          médio negativo nos 5 mercados. Verde e vermelho dizem a direção do movimento que JÁ aconteceu, não a do próximo.
          Use para entender o contexto antes de operar uma previsão (ex.: o par já andou 3 ATR na última hora; o spread
          está 3× o normal; sai o CPI dos EUA em 10 minutos).`,
      },
      {
        titulo: 'Barra de filtros',
        tabela: {
          colunas: ['Filtro', 'O que faz'],
          linhas: [
            { termo: 'Atualizado · última barra', texto: 'Quando o radar foi calculado e a última barra de 1 minuto recebida do MT5. “parado há …” = sem barra nova há mais de 10 min (mercado fechado ou MT5 parado): os números são do último pregão.' },
            { termo: 'Intensidade', texto: '●○○ informativo · ●●○ notável · ●●● forte. “só fortes” deixa só o que é raro. A escolha fica guardada no navegador.' },
            { termo: 'Período', texto: 'Mostra só os alertas dos períodos marcados (nenhum marcado = todos). Movimentos: 15 min conta como M15, 1 h como H1, 4 h como H4, o dia como D1.' },
            { termo: 'Símbolo', texto: 'Mostra só os alertas, eventos e notícias de um símbolo (o mesmo que clicar na linha dele no mapa).' },
            { termo: 'Tipos', texto: 'Mostra só os tipos marcados (nenhum = todos). Veja a tabela de tipos abaixo.' },
            { termo: 'Atualizar', texto: 'Recalcula agora. Sozinha, a página atualiza a cada minuto (eventos e notícias a cada 2 min).' },
          ],
        },
      },
      {
        titulo: 'Tipos de alerta',
        tabela: {
          colunas: ['Tipo', 'Quando aparece'],
          linhas: [
            { termo: 'Movimento', texto: 'O preço andou 2 ATR ou mais em 15 min, 1 h ou 4 h (ATR do período correspondente), ou 1,2 ATR diário ou mais desde a abertura do dia (00:00 UTC).' },
            { termo: 'Barra grande', texto: 'Uma barra fechada de M15, H1, H4 ou D1 com corpo de 1,5 ATR ou mais.' },
            { termo: 'Volume', texto: 'Volume de ticks 2,5× ou mais o normal. No M15 e no H1 o normal é o mesmo horário nos 20 dias anteriores (a abertura de Londres sempre tem mais volume que a madrugada).' },
            { termo: 'RSI', texto: 'RSI(14) ≥ 70 (sobrecompra) ou ≤ 30 (sobrevenda) na última barra fechada. 80/20 = notável, 90/10 = forte.' },
            { termo: 'Bollinger', texto: 'Fechamento fora das bandas (20 barras, 2 desvios). Nos períodos de 1 h ou mais vale mais.' },
            { termo: 'Compressão', texto: 'Bandas na menor largura das últimas 120 barras: volatilidade comprimida, que costuma vir antes de um movimento maior (sem dizer o lado).' },
            { termo: 'Sequência', texto: '6 ou mais barras seguidas fechando na mesma direção (M15/H1), 5 ou mais (H4/D1).' },
            { termo: 'Médias', texto: 'A média exponencial de 20 cruzou a de 50 nas últimas 3 barras do H1 ou H4.' },
            { termo: 'Rompimento', texto: 'Preço acima da máxima (abaixo da mínima) do dia anterior; notável se passar da máxima/mínima de 20 dias.' },
            { termo: 'Três topos', texto: 'Em M5 ou M15: três máximas na mesma altura (até 0,5 ATR) depois de uma alta de 2 ATR, com a linha de pescoço na menor mínima entre elas e altura de pelo menos 1 ATR. Notável = formado (a venda clássica espera fechar abaixo do pescoço); forte = já fechou abaixo. O botão <b>Vender</b> abre a página Operar com a venda calculada, com todas as travas. <b>Testado em 20/10/2026</b> (2022–26, todos os mercados, com spread): vender no padrão deu E[R] negativo em tudo, igual ou pior que vender num momento qualquer.' },
            { termo: 'Amplitude', texto: 'O dia já andou (máxima − mínima) 1,5× ou mais a média dos últimos 20 dias.' },
            { termo: 'Gap', texto: 'Reabertura com salto de 1 ATR (H1) ou mais depois de 30 min ou mais sem barras, nas últimas 24 h.' },
            { termo: 'Spread', texto: 'Spread 2× ou mais o típico do horário: entrar agora custa caro (e acima do limite da página Risco a ordem é recusada).' },
            { termo: 'Calendário', texto: 'Indicador de importância média ou alta das moedas do mercado nas próximas 2 h (●●● se faltam 15 min ou menos para um de importância alta) ou divulgado na última hora, com o atual comparado ao previsto.' },
            { termo: 'Notícia', texto: 'Documento de banco central das moedas do mercado visto nas últimas 6 h (●●○ se o Jev avaliou com relevância alta).' },
          ],
        },
      },
      {
        titulo: 'Alertas agora · Próximos eventos · Notícias',
        html: `O feed junta tudo, do mais forte para o mais fraco e, empatando, do mais próximo do agora. A coluna “Símbolo”
          traz o símbolo (clique para ver o detalhe) ou, em eventos e notícias, a moeda. Ao lado, os próximos eventos das
          48 h seguintes com a contagem regressiva (“em 34 min”, em vermelho quando faltam 30 min ou menos) e as últimas
          notícias dos bancos centrais. Índices e ações não têm notícias de empresa: vale o banco central da moeda deles.`,
      },
      {
        titulo: 'Mapa por símbolo',
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Variação (% · ATR)', texto: 'Quanto o preço andou em 15 min, 1 h, 4 h e no dia, em % e em ATR do período. Negrito = 2 ATR ou mais.' },
            { termo: 'RSI 14 · Bollinger', texto: 'RSI da última barra fechada de cada período, com fundo verde (≥ 70) ou vermelho (≤ 30), mais forte em 80/20. ▲/▼ = fechou acima/abaixo das bandas; ◆ = bandas comprimidas. Passe o mouse para ver %B, volume e sequência.' },
            { termo: 'Volume', texto: 'Volume da última barra fechada de M15 e H1 ÷ o normal do mesmo horário. Em laranja quando ≥ 2,5×.' },
            { termo: 'Spread', texto: 'Spread agora em pontos e quantas vezes o típico do horário.' },
            { termo: 'Próximo evento', texto: 'O próximo indicador das moedas do símbolo, com a contagem regressiva.' },
            { termo: 'Previsão', texto: 'A previsão mais recente do modelo principal (▲ alta / ▼ queda mais provável, e a diferença em pontos). “sinal” = passou na regra.' },
            { termo: 'Alertas', texto: 'Quantos alertas de preço o símbolo tem por intensidade (vermelho = forte, laranja = notável, cinza = informativo).' },
          ],
        },
      },
      {
        titulo: 'Força das moedas (só forex)',
        html: `Quanto cada moeda subiu ou caiu na última hora, nas últimas 4 h e 24 h contra a média das 8 moedas, calculado
          pelos 7 pares com USD. Ajuda a ver quem está movendo um par: se o EURUSD cai e o EUR está no meio da tabela, quem
          sobe é o USD — e os outros pares com USD tendem a mostrar o mesmo.`,
      },
      {
        titulo: 'Detalhe do símbolo (clique numa linha)',
        html: `Por período: fechamento, RSI (entre parênteses, contando a barra em formação — o que o gráfico do MT5 mostra
          agora), %B, as bandas, o ATR, o volume relativo, a sequência de barras e a tendência das médias 20/50. Embaixo, o
          dia de hoje, as máximas e mínimas de ontem e dos últimos 20 dias e a amplitude do dia comparada à média; ao lado,
          todos os alertas do símbolo. Os períodos são em UTC: o dia começa às 00:00 UTC (21:00 de Brasília) e a noite de
          domingo do forex conta como segunda-feira.`,
      },
    ],
  },

  // ======================================================================== previsões
  {
    id: 'previsoes',
    titulo: 'Página Previsões',
    resumo: 'Todas as previsões do último momento (3 modelos × 2 horizontes), o placar ao vivo e o histórico de cada símbolo.',
    blocos: [
      { imagem: { src: 'previsoes', legenda: 'Previsões do Forex, com um par selecionado (histórico à direita).' } },
      {
        titulo: 'Topo',
        tabela: {
          colunas: ['Elemento', 'Significado'],
          linhas: [
            { termo: 'Prever agora', texto: 'Calcula as previsões de todos os mercados neste minuto (gatilho MANUAL). Leva 1 a 2 minutos. As manuais aparecem nas tabelas mas ficam fora do placar. Use quando a página Operar reclamar de ATR velho.' },
            { termo: 'Linha de resultado', texto: 'Quantas previsões foram gravadas e para quantos pares; ou o motivo de não haver (mercado fechado, MT5 parado).' },
            { termo: 'Modelos em produção', texto: 'A versão em uso e até quando ela foi treinada. Selo “última previsão há N min” se passar de 90 min.' },
          ],
        },
      },
      {
        titulo: 'Tabela principal',
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Par', texto: 'O símbolo. <b>Clique na linha</b> para ver o histórico de 48 h à direita.' },
            { termo: 'Momento (UTC)', texto: 'O momento de decisão da previsão.' },
            { termo: 'Preço / Eventos / Eventos + Preço', texto: 'Os três modelos, cada um com duas colunas: <b>15 min</b> e <b>60 min</b>. Em cada célula, a barra de probabilidades e, quando passa no critério do mercado, o selo COMPRA ou VENDA.' },
          ],
        },
      },
      {
        titulo: 'Suas operações (resumo)',
        html: 'Os mesmos cartões da página Operar: operações fechadas nos últimos 30 dias, acerto, resultado em US$, R médio e profit factor. Veja Operar → Suas operações.',
      },
      {
        titulo: 'Placar dos modelos (últimos 90 dias)',
        html: `O resultado REAL das previsões automáticas, depois que o horizonte passa (o placar roda a cada 5 minutos).
          É a única evidência nova daqui em diante: tudo o mais foi medido no passado.`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Modelo / Horizonte', texto: 'Qual previsão está sendo medida.' },
            { termo: 'Resolvidas', texto: 'Previsões cujo horizonte já passou e ganharam o resultado real.' },
            { termo: 'Acerto da classe', texto: 'Em quantas a classe mais provável (alta, lateral ou queda) bateu com o que aconteceu. Como LATERAL é o mais comum, esse número sozinho engana.' },
            { termo: 'Sinais', texto: 'Quantas viraram COMPRA/VENDA pelo critério do mercado.' },
            { termo: 'E[R]', texto: 'Resultado médio por sinal, em R, simulando a operação com o stop e o alvo daquele mercado (página Risco) e saída no fim do horizonte, já com o spread.' },
            { termo: 'Acerto', texto: 'Fração dos sinais que terminaram com lucro.' },
          ],
        },
      },
      {
        titulo: 'Histórico (48 h)',
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Momento / Modelo / h', texto: 'Quando, qual modelo e qual horizonte (minutos).' },
            { termo: 'Probabilidades', texto: 'A barra de três cores.' },
            { termo: 'Sinal', texto: 'COMPRA, VENDA ou “—”.' },
            { termo: 'Resultado', texto: `O que aconteceu de fato: ${ALTA} ou ${QUEDA} se o preço andou mais de meio ATR já pagando o spread; senão LATERAL. “aguardando” = horizonte ainda não passou.` },
            { termo: 'Cor da linha', texto: '<b>Verde-claro</b> = a previsão bateu com o resultado; <b>vermelho-claro</b> = errou; sem cor = fora da conta (aguardando, ou LATERAL com a caixa desmarcada). Passe o mouse na linha para ver o que foi previsto.' },
            { termo: 'considerar LATERAL (caixa)', texto: '<b>Desmarcada</b> (padrão): compara só a direção — ALTA se P(alta) > P(queda), senão QUEDA — e os momentos que terminaram LATERAL ficam sem marca e fora da conta. <b>Marcada</b>: compara a classe mais provável das três, inclusive LATERAL, em todos os momentos resolvidos.' },
            { termo: 'Filtros', texto: '<b>P(alta) / P(queda)</b>: só as previsões cuja direção mais provável é esta. <b>P mín.</b>: só as com a probabilidade desse lado de pelo menos X%. <b>Horizonte</b> 15/60 min e <b>Modelo</b> (A · Preço, E · Eventos, B · Eventos + Preço). Nenhum botão marcado = todos.' },
            { termo: 'N acerto(s) · N erro(s) · %', texto: 'O total das linhas mostradas (com os filtros), a taxa de acerto, quantos LATERAL ficaram fora e quantos ainda aguardam resultado.' },
          ],
        },
      },
      {
        titulo: 'Backtest das previsões',
        html: `E se cada previsão já resolvida tivesse virado uma ordem? Escolha o símbolo (todos do mercado ou qualquer um dele, no seletor),
          o <b>período</b> (última hora, hoje, 24 h, 48 h, 7 ou 30 dias), o <b>horizonte</b> (15 ou 60 min), o <b>modelo</b>, o
          <b>lote</b>, a direção, a P mínima e se pula as previsões com LATERAL mais provável (como a roleta de tendência), e clique
          <b>Rodar backtest</b>. Cada previsão vira uma operação no lado mais provável, com o stop, o alvo e a saída no horizonte
          do placar, já com o spread. Aparecem as operações, os <b>acertos</b> (verde) e as <b>derrotas</b> (vermelho), o
          <b>resultado em US$</b>, o E[R], o total por par e cada operação.<br>
          O US$ converte o R pela perda no stop de hoje com o lote escolhido (ou o mínimo do símbolo; com o stop em US$ ligado,
          1 R = esse valor). Não inclui slippage nem previsões manuais. Previsões cujo horizonte ainda não terminou ficam de fora
          ("aguardando").`,
      },
    ],
  },

  // ======================================================================== operar
  {
    id: 'operar',
    titulo: 'Página Operar',
    resumo: 'A conta, o tíquete de ordem com todas as travas, as posições abertas e o histórico real das suas operações.',
    blocos: [
      { imagem: { src: 'operar-ouro', legenda: 'Operar no mercado Ouro, depois de clicar em “Calcular COMPRA”. Números de conta e tíquetes foram ocultados na captura.' } },
      {
        titulo: 'Avisos do topo',
        html: `Em <b>LIVE</b> aparece o aviso de conta real (cada ordem usa dinheiro de verdade). Em <b>SHADOW</b>, o aviso
          de que nada vai para o MT5 e como ligar o envio.`,
      },
      {
        titulo: 'Conta',
        tabela: {
          colunas: ['Linha / botão', 'Significado'],
          linhas: [
            { termo: 'Conta', texto: 'Login, servidor da corretora e o tipo (DEMO ou REAL), como o EA informou.' },
            { termo: 'Saldo / patrimônio', texto: 'Saldo = dinheiro sem contar posições abertas. Patrimônio (equity) = saldo + resultado das posições abertas.' },
            { termo: 'Margem', texto: 'Quanto a corretora separou para manter as posições e o nível de margem (patrimônio ÷ margem).' },
            { termo: 'EA', texto: '<b>conectado</b> se o último heartbeat tem até 30 s. Ao lado: há quantos segundos, e se o envio de ordens está LIGADO no EA (InpExecute) ou se ele só registra.' },
            { termo: 'Sistema', texto: 'O modo do servidor (SHADOW, DEMO, LIVE).' },
            { termo: 'BLOCK', texto: 'Liga/desliga o bloqueio de entradas novas. Fechar posições continua liberado.' },
            { termo: 'FLATTEN (fechar tudo)', texto: 'Cancela o que está pendente, liga o BLOCK e manda o EA fechar TODAS as posições deste sistema. Use em emergência.' },
            { termo: 'Trava de perda', texto: 'Com a perda do dia (desde 00:00 UTC) ou da semana (desde segunda) acima destes limites, nenhuma entrada nova, nem da roleta. Os valores vêm do perfil de risco aplicado na página Risco (seção 1); não há mais escolha à parte nesta página.' },
            { termo: 'Linha “Limites”', texto: 'Resumo do que está em vigor: teto por operação, posições, stop/alvo globais e lote máximo.' },
          ],
        },
      },
      {
        titulo: 'Nova ordem',
        tabela: {
          colunas: ['Elemento', 'Significado'],
          linhas: [
            { termo: 'Par', texto: 'Os símbolos do mercado escolhido no topo.' },
            { termo: 'Lote / Salvar lote', texto: 'O tamanho da ordem. “Salvar lote” deixa esse valor como padrão do tíquete e da página Hoje.' },
            { termo: 'Barras Preço · Eventos · Eventos + Preço (60 min)', texto: 'As previsões mais recentes do par, para consulta antes de decidir.' },
            { termo: 'Calcular COMPRA / Calcular VENDA', texto: 'Monta a ordem e confere todas as travas. Nada é enviado nesta etapa.' },
            { termo: 'Entrada (cotação agora)', texto: 'O preço de compra (ask) ou de venda (bid) mandado pelo EA.' },
            { termo: 'Stop / alvo (aprox.)', texto: 'Preços aproximados do stop (vermelho) e do alvo (verde). O EA recalcula sobre o preço real da execução, com as mesmas distâncias.' },
            { termo: 'Perda máxima se o stop bater', texto: 'Em US$ e em % do saldo: é o seu 1 R. Distância do stop × lote × valor do ponto (da corretora).' },
            { termo: 'Ganho se o alvo bater', texto: 'Em US$.' },
            { termo: 'Saída por tempo', texto: 'Depois de quantos minutos a posição é fechada se nem o stop nem o alvo vierem. No ouro: 15 min.' },
            { termo: 'COMPRAR / VENDER (botão azul)', texto: 'Só aparece com todas as travas ✓. Pede confirmação e grava a ordem para o EA (válida por 30 s).' },
          ],
        },
      },
      {
        titulo: 'As travas (✓ passou · ✗ bloqueou)',
        imagem: { src: 'operar-travas', legenda: 'Prévia de uma compra de 0,01 XAUUSD: perda máxima, ganho no alvo, saída em 15 min e as travas, todas ✓.' },
        html: 'Cada linha mostra o que foi medido e o limite. Uma única ✗ impede a ordem. Os limites vêm da página Risco.',
        tabela: {
          colunas: ['Trava', 'O que confere'],
          linhas: [
            { termo: 'Símbolo', texto: 'O símbolo tem a especificação da corretora (vem do MT5); sem ela o risco não pode ser calculado.' },
            { termo: 'EA conectado', texto: 'Heartbeat do EA nos últimos 30 s; sem ele ninguém executaria a ordem.' },
            { termo: 'Modo × conta', texto: 'SHADOW só registra; DEMO exige conta demo; LIVE exige conta real.' },
            { termo: 'BLOCK / FLATTEN', texto: 'Ambos desligados.' },
            { termo: 'Mercado habilitado', texto: 'O mercado está ativo na página Risco (seção 4).' },
            { termo: 'Cotação', texto: 'A cotação do EA tem no máximo 30 s.' },
            { termo: 'ATR (última previsão)', texto: 'O ATR que define o stop vem da última previsão do símbolo e tem no máximo 90 min. Fora do horário das previsões (21:00–00:00 UTC e fim de semana) ele envelhece: use “Prever agora”.' },
            { termo: 'Lote', texto: 'Entre o lote mínimo da corretora e o lote máximo da página Risco, no passo de 0,01.' },
            { termo: 'Risco da ordem', texto: 'A perda máxima não passa do teto por operação (o menor entre o % do saldo e o valor em US$).' },
            { termo: 'Risco-alvo do mercado', texto: 'Perda no stop até o risco-alvo do mercado (seção 3.4). Com o lote mínimo acima do alvo: ALLOW_UP_TO_CAP deixa passar até o teto; SKIP recusa.' },
            { termo: 'Posições abertas', texto: 'Abaixo do limite da conta inteira (seção 3.1), contando as ordens a caminho.' },
            { termo: 'Posições no mercado', texto: 'Abaixo do limite do mercado (seção 3.4), contando só as posições deste mercado.' },
            { termo: 'Par livre', texto: 'Só uma posição por símbolo.' },
            { termo: 'Risco aberto total', texto: 'O risco de todas as posições abertas + esta cabe no limite (todas precisam ter stop).' },
            { termo: 'Risco aberto no mercado', texto: 'Soma do risco das posições deste mercado + a nova, até o risco aberto do mercado (seção 3.4).' },
            { termo: 'Exposição líquida a USD', texto: 'No forex, a soma do risco na direção do dólar cabe no limite.' },
            { termo: 'Perda do dia / da semana', texto: 'A queda do patrimônio desde 00:00 UTC (dia) e desde segunda 00:00 UTC (semana) está abaixo dos limites de perda do perfil de risco (página Risco, seção 1).' },
            { termo: 'Sessão até a saída por tempo', texto: 'A saída por tempo precisa cair dentro da sessão do símbolo (com a folga da página Risco): sem isso, a posição ficaria presa no fechamento (sexta à noite, ou o fechamento diário das ações) e a corretora recusaria o fechamento. Vale também na roleta. O EA 2.5 informa o fim da sessão; com o EA antigo, a trava passa avisando.' },
            { termo: 'Nível de margem', texto: 'Acima do mínimo (folga antes do stop-out da corretora).' },
            { termo: 'Spread (gate 3)', texto: 'O spread agora é no máximo N vezes o típico daquele horário.' },
            { termo: 'Fora de blackout (gate 2)', texto: 'Nenhum evento de importância ALTA da moeda entre N minutos antes e N minutos depois de agora.' },
          ],
        },
      },
      {
        titulo: 'Posições abertas (deste sistema)',
        html: `As posições da conta conectada agora. Com o EA 2.8, aparecem também as abertas <b>fora do sistema</b> (à mão
          no MT5, no celular ou por outro robô), com o selo “fora do sistema”: elas entram nas travas (posições, um por
          símbolo, risco aberto — sem stop, o risco é desconhecido e as entradas novas param), mas o sistema não as fecha,
          não edita e não aplica trailing nem saída por tempo. Acima da tabela: o botão <b>“Prorrogar com lucro na saída por tempo”</b> e como estão o
          trailing e o stop em US$. Embaixo de cada posição, uma linha com o stop de entrada (em ATR ou em US$), a perda
          planejada, quantas vezes o trailing moveu o stop e se a posição foi prorrogada.`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Ticket', texto: 'Número da posição no MT5.' },
            { termo: 'Par / Lado / Lote', texto: 'O que está aberto.' },
            { termo: 'Abertura', texto: 'Preço de entrada.' },
            { termo: 'SL', texto: 'Stop no servidor da corretora: fecha com perda se o preço chegar lá, mesmo com o seu computador desligado. Embaixo, quanto dá em US$ se bater: negativo = perda; positivo = lucro já garantido (trailing ou prorrogação).' },
            { termo: 'TP', texto: 'Alvo no servidor da corretora: fecha com lucro. Embaixo, quanto dá em US$.' },
            { termo: 'Resultado', texto: 'Lucro ou prejuízo flutuante, em US$.' },
            { termo: 'Origem', texto: 'Roleta · tendência (lado mais provável), Roleta · pullback (LATERAL, contra a banda de Bollinger; com o %B da entrada) ou Manual; o modelo, o horizonte e a probabilidade do lado na hora da entrada.' },
            { termo: 'Fecha em', texto: 'Contagem até a saída por tempo (minutos:segundos). “(prorrogada)” = é o fim da prorrogação com lucro. “só SL/TP” = ordem sem saída por tempo.' },
            { termo: 'Desde (UTC)', texto: 'Quando abriu.' },
            { termo: 'Editar', texto: 'Abre os campos de stop (SL) e alvo (TP) com quanto cada um dá em US$. “Aplicar no servidor” manda o EA mover os dois na corretora (JevExecutor 2.6). O stop pode se afastar, mas a perda nele não passa do teto por operação da página Risco; não dá para tirar o stop; stop e alvo precisam ficar do lado certo e além da distância mínima da corretora. O pedido aparece em Ordens recentes como “mover stop”. Trailing e proteção, se ligados, continuam podendo subir o stop.' },
            { termo: 'Fechar', texto: 'Manda o EA fechar agora (fechar nunca é bloqueado pelo BLOCK).' },
          ],
        },
      },
      {
        titulo: 'Suas operações (30 dias)',
        html: 'O resultado REAL das ordens que você mandou, inclusive as que a corretora fechou sozinha no stop ou no alvo. Diferente do placar dos modelos, que mede as previsões. Só conta a conta conectada agora (a da última conexão do EA): ao trocar da demo para a real, o placar começa zerado e a demo não se mistura.',
        tabela: {
          colunas: ['Cartão / coluna', 'Significado'],
          linhas: [
            { termo: 'Operações', texto: 'Quantas foram fechadas no período.' },
            { termo: 'Acerto', texto: 'Fração com lucro.' },
            { termo: 'Resultado', texto: 'Soma em US$ (verde lucro, vermelho prejuízo).' },
            { termo: 'R médio', texto: 'Resultado médio dividido pela perda máxima planejada. +0,10 R por operação é o mínimo que o documento mestre pede.' },
            { termo: 'Profit factor', texto: 'Soma dos ganhos ÷ soma das perdas. Acima de 1 = ganhou.' },
            { termo: 'Fechada (UTC)', texto: 'Quando a posição foi fechada.' },
            { termo: 'Entrada → saída', texto: 'Preço de abertura e de fechamento.' },
            { termo: 'Saída', texto: '<b>stop (servidor)</b>, <b>alvo (servidor)</b>, <b>botão / saída por tempo</b> (fechada pelo sistema), <b>manual no MT5</b> ou <b>stop-out da corretora</b> (falta de margem).' },
            { termo: 'Resultado / R', texto: 'Em US$ e em múltiplos do risco planejado (−1,00 = stop cheio).' },
          ],
        },
      },
      {
        titulo: 'Ordens recentes',
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: '# / Quando', texto: 'Número da ordem no sistema e quando foi criada.' },
            { termo: 'Ação', texto: 'compra, venda ou “fechar #ticket”.' },
            { termo: 'Risco', texto: 'A perda máxima calculada no envio.' },
            { termo: 'Status', texto: '<b>PENDING</b> esperando o EA · <b>SENT</b> entregue ao EA · <b>FILLED</b> executada · <b>REJECTED</b> recusada (motivo no Detalhe) · <b>EXPIRED</b> o EA não buscou a tempo (está ligado?) · <b>SHADOW</b> só registrada.' },
            { termo: 'Detalhe', texto: 'SL e TP reais da execução, ou a mensagem de erro da corretora / do EA.' },
          ],
        },
      },
      { html: 'A página se atualiza sozinha a cada 5 segundos.' },
    ],
  },

  // ======================================================================== eventos
  {
    id: 'eventos',
    titulo: 'Página Eventos (mapa de eventos)',
    resumo: 'O que cada divulgação costumou fazer com o preço de cada símbolo desde 2022: quanto andou e para que lado, conforme o número veio acima ou abaixo do esperado.',
    blocos: [
      {
        titulo: 'Como ler',
        html: `Para cada tipo de divulgação de importância alta ou moderada e cada símbolo afetado (no forex, os pares com
          a moeda; nos outros mercados, a moeda de lucro: ouro, bitcoin, petróleo e índices dos EUA em USD, DE30 em EUR…):
          <ul><li><b>Anda 15/60 min</b>: o tamanho médio do movimento depois da divulgação, em ATR (o ATR das 14 barras de
          15 min antes). Inclui o salto do primeiro minuto.</li>
          <li><b>Acima / abaixo do esperado</b>: a partir de <b>2 min depois</b> (quando dá para entrar) até +15 min, em que
          % das vezes subiu, quantos casos e o movimento médio. Verde = subiu em 65%+; vermelho = caiu em 65%+.</li>
          <li><b>Depende da surpresa</b>: a diferença entre as duas. O selo <b>forte</b> (ou <b>média</b>) diz que a
          diferença resiste ao acaso; sem selo, pode ser sorte — com ~1.000 combinações, algumas parecem fortes à toa.</li>
          <li><b>Spread</b>: o custo de 1 spread na hora, em ATR. Precisa ser bem menor que o movimento.</li>
          <li><b>+N junto</b>: outras divulgações no mesmo minuto (CPI cheio, núcleo, mensal e anual saem juntos): o
          movimento é do conjunto, não dá para separar o efeito de cada uma.</li></ul>
          No topo, <b>Próximos eventos (48 h)</b> cruza o calendário com esse histórico. É descritivo, não regra validada.
          <b>Atualizar o mapa deste mercado</b> refaz a conta (segundos no ouro, até 7 min nos índices).`,
      },
    ],
  },

  // ======================================================================== laboratório
  {
    id: 'laboratorio',
    titulo: 'Página Laboratório',
    resumo: 'Que regra de entrada e de saída teria dado mais resultado num mercado, escolhida num período e confirmada noutro.',
    blocos: [
      {
        titulo: 'Como funciona',
        html: `Escolha o mercado no topo e clique em <b>Rodar o laboratório deste mercado</b> (1 a 7 min; roda como as tarefas
          da página Saúde). O laboratório testa milhares de combinações — modelo (Preço, Eventos, Eventos + Preço), horizonte
          (15 ou 60 min), momento (todos, só depois de evento, só na hora cheia), probabilidade mínima do lado, margem mínima
          entre alta e queda, pular LATERAL, stop (1; 1,5; 2 ATR) e alvo (1; 1,5; 2 × o stop) — sobre as previsões <b>fora
          da amostra</b>: cada mês previsto por um modelo que não tinha visto aquele mês (walk-forward), mais o cofre e as
          previsões ao vivo já passadas. Cada saída é simulada minuto a minuto no M1, com o spread.`,
      },
      {
        titulo: 'Escolha, confirmação e ao vivo (por que não "o melhor dos últimos dias")',
        html: `Testando milhares de combinações, alguma sempre parece ótima por acaso. Por isso a combinação é <b>escolhida</b>
          num período (o começo do walk-forward) e só é <b>recomendada</b> se também for positiva no período de
          <b>confirmação</b> (o resto do walk-forward + o cofre), que não participou da escolha, com pelo menos 20
          operações. O <b>ao vivo</b> é a terceira conferência, só mostrada. A tabela <b>As melhores na escolha</b> mostra em
          verde as que se mantiveram e em vermelho as que não: se quase todas ficam vermelhas, o ranking é ruído e o mercado
          não tem regra que funcione. <b>Sobrevivência</b> resume isso; a taxa entre todas as positivas é a comparação justa,
          porque as melhores costumam ser variações da mesma regra.`,
      },
      {
        titulo: 'O que a página mostra',
        tabela: {
          colunas: ['Elemento', 'Significado'],
          linhas: [
            { termo: 'Configuração recomendada', texto: 'A regra, e por período: operações (e por mês), E[R], acerto, profit factor, pior queda acumulada em R e o equivalente em US$ com o lote mínimo (1 R pelo ATR mediano dos símbolos).' },
            { termo: 'Evidência forte / moderada / fraca', texto: 'Pela fração das combinações positivas na escolha que seguiram positivas na confirmação e pelo número de operações: fraca (abaixo de 30%) = provavelmente sorte; moderada = uma família de regra com pouca amostra; forte = 50% ou mais com 50+ operações em cada período.' },
            { termo: 'Avisos', texto: 'Menos de 50 operações num período, ao vivo ainda sem operações ou negativo, menos de 2 operações por mês.' },
            { termo: 'Como seguir', texto: 'O que mudar na página Risco (stop, alvo, saída por tempo do mercado) e a regra de entrada. A roleta não filtra por probabilidade: ela entra em toda previsão.' },
            { termo: 'Ligar a roleta com esta configuração', texto: 'Liga a roleta do mercado com a regra recomendada: modelo e horizonte, só os momentos do filtro (ex.: só depois de evento), P e margem mínimas, pular LATERAL, stop e alvo próprios e saída por tempo no horizonte, com o lote escolhido. Substitui a roleta ligada no mercado; na conta real pede digitar LIVE. A roleta ignora parte das travas de risco (teto e alvo de risco, dólar, margem, spread, blackout); o stop em US$, se ligado, prevalece sobre o stop/alvo da configuração. A página Roleta mostra a configuração em uso e para a roleta.' },
            { termo: 'Nenhuma configuração passou', texto: 'Nenhuma combinação positiva na escolha se manteve na confirmação: a recomendação é não operar o mercado.' },
            { termo: 'A roleta de hoje', texto: 'A regra da roleta (modelo B, toda previsão, pula LATERAL, saídas de hoje) nos mesmos períodos, para comparar.' },
          ],
        },
      },
      {
        titulo: 'Limites',
        html: `É o passado: mercado muda. Poucas operações (o normal das regras filtradas) deixam muita margem para sorte.
          Não considera slippage nem o atraso real do clique. A página só recomenda: nada muda no sistema até você mudar
          na página Risco.`,
      },
    ],
  },

  // ======================================================================== roleta
  {
    id: 'roleta',
    titulo: 'Página Roleta russa',
    resumo: 'Ordens automáticas por mercado: cada previsão nova vira uma ordem em cada símbolo, no lado mais provável, até você parar.',
    blocos: [
      {
        titulo: 'Como funciona',
        html: `Você liga a roleta de um mercado escolhendo o <b>horizonte</b> (15 ou 60 min), o <b>modelo</b> e o <b>lote</b>
          (vazio = o mínimo de cada símbolo). A cada 10 segundos o servidor confere se saiu previsão nova desse modelo e
          horizonte (na hora cheia e 2 min depois de cada divulgação). Para cada símbolo: se <b>LATERAL</b> é o mais provável,
          pula; senão envia uma ordem no lado mais provável — COMPRA se P(alta) &gt; P(queda), senão VENDA — com o stop e o
          alvo do mercado (página Risco) e <b>saída por tempo no horizonte escolhido</b>. Previsão com mais de 10 min não vira
          ordem (entrar atrasado leva a vantagem embora). A roleta continua ligada mesmo se o servidor reiniciar.`,
      },
      {
        titulo: 'Quais travas valem',
        tabela: {
          colunas: ['Trava', 'Na roleta'],
          linhas: [
            { termo: 'EA conectado · Modo × conta · Cotação · ATR · Lote', texto: '<b>Valem.</b> São técnicas: sem elas a ordem sairia com stop errado ou na conta errada.' },
            { termo: 'BLOCK / FLATTEN (página Operar)', texto: '<b>Valem.</b> Ligar o BLOCK recusa as entradas da roleta (ela continua ligada, mas nada sai).' },
            { termo: 'Perda do dia / da semana', texto: '<b>Valem e param TODAS as roletas</b> (disjuntor). O motivo aparece no quadro do mercado.' },
            { termo: 'Posições abertas (conta e mercado) · Par livre · Mercado habilitado · Risco aberto (conta e mercado) · Sessão', texto: '<b>Valem</b> (desde 19/10, escolha do operador): com o limite de posições cheio, o mesmo símbolo já aberto ou o mercado desligado na página Risco, a previsão é recusada.' },
            { termo: 'Risco da ordem · Risco-alvo do mercado · Exposição a USD · Margem · Spread · Blackout', texto: '<b>Ignoradas</b> (escolha do operador em 14/10). Ficam registradas na ordem como “ignorada na roleta”.' },
          ],
        },
      },
      {
        titulo: 'Estratégia: Tendência, Pullback (teste) ou as duas',
        html: `<b>Tendência</b> (padrão): a descrita acima — o lado mais provável, pulando LATERAL.<br>
          <b>Pullback no LATERAL</b> (teste pedido em 14/10): só entra quando <b>LATERAL é o mais provável</b> e o preço está
          na banda de Bollinger (20, 2, M15): <span class="up">COMPRA</span> na banda inferior ou abaixo,
          <span class="down">VENDA</span> na superior ou acima. A <b>Banda</b> diz quão perto: “fora das bandas” (%B ≤ 0 ou
          ≥ 1), “até 10%” (%B ≤ 0,1 ou ≥ 0,9) ou “até 20%”. As bandas e o preço vêm do Radar no momento da decisão; sem
          barra recente, não entra. A ordem sai no horizonte escolhido (15 ou 60 min), com o stop e o alvo da página Risco.
          <br><b>Antes de ligar:</b> no teste fora da amostra (abr–set/2026, ~100 mil momentos) esta regra deu resultado
          médio <b>negativo</b> em forex (−0,19 R), índices (−0,14 a −0,17), ações (−0,27) e bitcoin (−0,05 a −0,07) e
          <b>zero</b> no ouro: o preço quase não volta da banda nesses minutos e o spread fica com a diferença. O quadro
          mostra esse número em vermelho e o placar real do pullback separado da tendência.<br>
          <b>Tendência + pullback</b>: as duas juntas, aproveitando todas as previsões. Previsão com ALTA ou QUEDA mais
          provável segue a tendência; com LATERAL mais provável vai para o pullback (entra só se o preço estiver na banda —
          LATERAL no meio das bandas continua sem ordem, porque não há lado). O placar mostra as duas pernas separadas.`,
      },
      {
        titulo: 'Os números de cada quadro',
        tabela: {
          colunas: ['Linha', 'Significado'],
          linhas: [
            { termo: 'Esta regra nas previsões já resolvidas', texto: 'O que a roleta teria dado nos últimos 30 dias com o modelo e o horizonte escolhidos: E[R] por operação, número de operações, soma em R e fração com lucro, já com o spread (sem slippage). <b>Vermelho = perdeu na média.</b> Até 14/10: forex e ações negativos; índices a 60 min +0,05 R.' },
            { termo: 'Ordens da roleta', texto: 'Enviadas, puladas (LATERAL) e recusadas pelas travas que continuam valendo.' },
            { termo: 'fechadas · US$ · E[R]', texto: 'O resultado REAL das posições da roleta que já fecharam (o que o EA reportou).' },
            { termo: 'abertas agora', texto: 'Posições da roleta abertas, quanto se perde se todas derem stop e o resultado aberto.' },
          ],
        },
      },
      {
        titulo: 'Parar',
        html: `<b>PARAR ROLETA RUSSA</b> (vermelho, no topo) para todos os mercados; o botão do quadro para só aquele. Parar
          impede entradas novas; as posições abertas saem pelo tempo, pelo stop ou pelo alvo — para fechar já, use
          <b>Fechar</b> ou <b>FLATTEN</b> na página Operar. Em modo LIVE (conta real) o sistema pede para digitar LIVE ao ligar.`,
      },
    ],
  },

  // ======================================================================== stop em US$
  {
    id: 'stop-dolar',
    titulo: 'Risco · Stop e alvo em US$ e trailing',
    resumo: 'Parte 2 da página Risco: stop e alvo de cada ordem em dólares (prevalecem sobre tudo) e trailing stop das posições abertas.',
    blocos: [
      {
        titulo: 'Stop e alvo por ordem, em US$',
        html: `Com a caixa <b>“Usar stop e alvo em dólares”</b> marcada, <b>toda ordem nova</b> — da página Operar, da Hoje
          e da Roleta — sai com stop e alvo que perdem/ganham exatamente esses valores com o lote da ordem, em qualquer
          mercado. Isso tem <b>prioridade</b> sobre o stop e o alvo em ATR da página Risco (os gerais e os de cada mercado).
          Desmarcada, voltam os de ATR. Posições já abertas não mudam.<br>
          A linha de baixo mostra quantas vezes o alvo é maior que o stop, quanto é preciso acertar para empatar (antes do
          spread) e quanto do saldo o stop representa (em vermelho acima de 5%).<br>
          <b>Atenção:</b> um stop fixo em dólares fica perto ou longe do preço conforme o símbolo e o lote. A tabela
          <b>“Onde fica o stop em cada símbolo”</b> mostra a distância em preço e em pontos com o lote escolhido, e o spread
          de agora. <span class="down">“perto demais”</span> = o stop cairia a menos de 2 spreads (ou do mínimo da corretora):
          a ordem é recusada pela trava <b>Stop em US$ × spread</b>, que vale também na Roleta.`,
      },
      {
        titulo: 'Trailing stop',
        html: `Com <b>“Ativar trailing stop”</b>, o servidor confere a cada 5 segundos todas as posições abertas pelo sistema
          e pede ao EA para mover o stop <b>a favor</b> (nunca contra; o alvo fica onde está):
          <ul><li>quando o lucro chega a <b>“Começar com lucro de”</b> (padrão US$ 2), o stop vai para o preço de entrada —
          a operação não dá mais prejuízo (zero a zero);</li>
          <li>depois, o stop segue o preço deixando <b>“Distância do stop”</b> do lucro para trás (com US$ 2: lucro de
          US$ 5 → o stop trava US$ 3);</li>
          <li>só pede de novo quando trava pelo menos <b>“Mover a cada”</b> a mais (evita mexer a cada tique).</li></ul>
          A tabela ao lado mostra o efeito com os valores da tela. O trailing vale com ou sem o stop em dólares ligado, e
          também para as posições que já estavam abertas. <b>Precisa do EA JevExecutor 2.2</b> (rode
          <code>mql5\\install.ps1</code> e recarregue o EA no gráfico); com o EA antigo nada é movido e o servidor avisa no
          log. Na página Operar, os pedidos aparecem como ordens <b>MODIFY</b>.`,
      },
      {
        titulo: 'Proteção de lucro por mercado',
        html: `Com <b>“Proteger lucro”</b> ligado, nos mercados marcados (padrão: ouro, bitcoin, índices e energia), quando o
          lucro de uma posição passa de <b>“Quando o lucro passar de”</b> (padrão US$ 3), o servidor pede ao EA para levar o
          stop à entrada mais <b>“O stop garante”</b> (padrão US$ 0,20; na venda, à entrada menos esse valor). A partir daí o
          pior resultado da posição é esse lucro, salvo gap ou slippage. Confere a cada 5 s, só move a favor e vale também
          para as posições já abertas. Independe do trailing: com os dois ligados, vale o stop que protege mais. Se a
          corretora não aceitar o stop tão perto do preço, espera o lucro crescer. Precisa do EA JevExecutor 2.2 ou mais novo.`,
      },
      {
        titulo: 'Prorrogação com lucro na saída por tempo',
        html: `Liga e desliga aqui ou pelo botão da página <b>Operar</b>, e vale para <b>todas</b> as posições (roleta e
          manuais). Quando uma posição chega ao fim do tempo <b>com lucro</b> de pelo menos <b>“Lucro mínimo”</b>, em vez de
          fechar a mercado ela passa a ser <b>acompanhada</b> (a cada 5 s):
          <ul><li>o <b>stop</b> garante <b>“O stop garante”</b> % do <b>maior</b> lucro visto (nunca pior que o do trailing);</li>
          <li>o <b>alvo</b> fica à frente do preço, a <b>“O alvo fica além do preço”</b> % do maior lucro;</li>
          <li>quando o lucro passa a máxima anterior em <b>“Nova máxima conta com”</b> %, o stop sobe, o alvo vai mais à frente
          e a janela renova;</li>
          <li>sem nova máxima em <b>“Fecha sem nova máxima em”</b> minutos, ou no <b>“Teto da prorrogação”</b>, fecha a mercado.</li></ul>
          Com os padrões (50%, 50%, +10%, 10 min, teto 60 min): lucro de US$ 2 no fim do tempo → stop garante US$ 1; se o
          lucro chega a US$ 4, o stop passa a garantir US$ 2 e o alvo vai para +US$ 6. Sem lucro suficiente, sem cotação
          recente do EA ou se a corretora não aceitar o stop tão perto, fecha como sempre; se o EA recusar o início, fecha na
          volta seguinte. <b>Precisa do EA JevExecutor 2.4</b>; com o EA antigo, fecha por tempo como antes. Não há estudo de
          que isso melhora o resultado: é uma escolha de gestão do operador (os modelos foram avaliados com a saída por tempo).`,
      },
      {
        titulo: 'Salvar',
        html: `<b>Salvar e aplicar</b> pede confirmação e vale na hora. Fica gravado no mesmo arquivo da página Risco
          (<code>config/application.yml</code>, bloco <code>trading.risk.money</code>) e entra no histórico de alterações dela.`,
      },
    ],
  },

  // ======================================================================== risco
  {
    id: 'risco',
    titulo: 'Página Risco',
    resumo: 'Todas as regras que o sistema confere antes de cada ordem. Mudou e salvou: vale na hora, sem reiniciar.',
    blocos: [
      { imagem: { src: 'risco', legenda: 'A página Risco inteira: limites da conta, travas de cada ordem, saídas, orçamento por mercado.' } },
      {
        titulo: 'Como funciona',
        html: `Os valores ficam em <code>config/application.yml</code> (ao lado do jar, fora do git) e valem por cima do
          padrão do sistema. Cada gravação é auditada no histórico. Valor fora do permitido é recusado e nada é gravado. Os
          valores “na prática” usam o saldo atual informado pelo EA. <b>Regra de ouro: quando dois limites se aplicam, vale
          o menor.</b><br>A página tem três partes: <b>1 · Perfil pronto</b> (um clique), <b>2 · Stop e alvo em US$ e
          trailing</b> (prevalece sobre tudo) e <b>3 · Ajuste fino</b> (campo a campo, as seções 3.1 a 3.4 abaixo).`,
      },
      {
        titulo: '1 · Perfil pronto',
        html: `Cinco conjuntos completos e coerentes, do <b>Ultra conservador</b> ao <b>Ultra arrojado</b>. Clicar num deles
          (e confirmar) substitui os limites da conta (3.1), as travas de ordem que ele define (3.2: lote máximo, saída por
          tempo, blackout, spread), as saídas (3.3: stop 1,5 ATR e alvo 1,5 R — as que foram testadas — em todos os
          mercados; o ouro mantém a saída em 15 min da regra dele) e o orçamento de cada mercado (3.4), inclusive a perda do
          dia e da semana que trava as entradas. <b>Não mexe</b> no stop/alvo em US$ nem no trailing (parte 2).<br>
          Cada cartão mostra a perda máxima por ordem com o saldo de agora, quantas posições ao mesmo tempo e quando o
          sistema para de abrir ordens no dia e na semana. O perfil em vigor fica em verde com <b>EM VIGOR</b>; depois de
          um ajuste fino na parte 3, nenhum fica em verde. Nos perfis conservadores, com banca pequena, símbolos cujo lote
          mínimo arrisca mais que o teto (ex.: ouro, ~US$ 16 por ordem) ficam sem ordem — é o perfil funcionando. Os
          números ficam no <code>application.yml</code> (<code>risk.presets</code>).`,
      },
      {
        titulo: '1 · Limites da conta inteira',
        tabela: {
          colunas: ['Regra', 'Significado'],
          linhas: [
            { termo: 'Teto de perda por operação (%)', texto: 'Perda máxima de UMA operação se o stop bater, em % do saldo.' },
            { termo: 'Teto de perda por operação (US$)', texto: 'O mesmo teto em dinheiro (0 = sem teto em dinheiro). Vale o menor dos dois. Útil com banca pequena.' },
            { termo: 'Risco aberto somado (%)', texto: 'Soma do risco até o stop de todas as posições abertas.' },
            { termo: 'Perda máxima no dia / na semana (%)', texto: 'Atingiu: sem entradas novas até 00:00 UTC (dia) ou até segunda (semana). São os limites do perfil Padrão.' },
            { termo: 'Posições abertas ao mesmo tempo', texto: 'Somando todos os mercados.' },
            { termo: 'Exposição líquida ao dólar (%)', texto: 'No forex: comprar EURUSD = vender USD; comprar USDJPY = comprar USD. Limita a aposta somada no dólar.' },
            { termo: 'Política do lote mínimo', texto: '<b>SKIP</b>: se nem 0,01 cabe no risco-alvo do mercado, não opera. <b>ALLOW_UP_TO_CAP</b>: opera com 0,01 desde que não passe do teto por operação.' },
          ],
        },
      },
      {
        titulo: '2 · Travas de cada ordem',
        tabela: {
          colunas: ['Trava', 'Significado'],
          linhas: [
            { termo: 'Lote padrão do tíquete / Lote máximo por ordem', texto: 'O lote que o tíquete já traz e o maior aceito (o EA tem o próprio teto, InpMaxLot; vale o menor).' },
            { termo: 'Saída por tempo (min)', texto: 'Fecha depois de N minutos se stop e alvo não vierem (0 = só SL/TP). Um mercado pode ter o seu (seção 4).' },
            { termo: 'Desvio máximo na execução (pontos)', texto: 'Diferença aceita entre o preço pedido e o executado.' },
            { termo: 'Validade da ordem (s)', texto: 'O EA recusa ordem mais velha que isso.' },
            { termo: 'Idade máxima da cotação (s) / do ATR (min)', texto: 'Dados mais velhos que isso = sem ordem.' },
            { termo: 'Blackout antes / depois de evento alto (min)', texto: 'Gate 2: sem entrada perto de indicador de importância ALTA da moeda (o preço salta e o spread abre).' },
            { termo: 'Spread máximo (× o típico)', texto: 'Gate 3: spread atual até N vezes a mediana daquele horário nos últimos 20 dias.' },
            { termo: 'Nível de margem mínimo (%)', texto: 'Sem entrada nova abaixo disso.' },
          ],
        },
      },
      {
        titulo: '3 · Saídas',
        tabela: {
          colunas: ['Saída', 'Significado'],
          linhas: [
            { termo: 'Stop (× ATR) = 1 R', texto: 'Distância do stop em múltiplos do ATR (tamanho médio de uma barra de 15 min). Stop maior = menos stops por ruído, mas perda maior em US$ com o mesmo lote.' },
            { termo: 'Alvo (× stop)', texto: 'O alvo em múltiplos do stop (1,5 = ganha 1,5 R ou perde 1 R).' },
          ],
        },
        html: 'Valem para todos os mercados que não tiverem stop e alvo próprios na seção 4.',
      },
      {
        titulo: '4 · Orçamento por mercado',
        imagem: { src: 'risco-mercados', legenda: 'Orçamento por mercado: o ouro (Metais) tem saída em 15 min, stop 1,5 ATR e alvo 1,5 × stop próprios.' },
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Ativo', texto: 'Se o mercado pode receber ordens.' },
            { termo: 'Risco-alvo por operação (%)', texto: 'O tamanho ideal de cada operação. Com banca pequena, o lote mínimo costuma passar disso; aí manda a política de lote mínimo.' },
            { termo: 'Risco aberto (%) / Posições', texto: 'Limites só deste mercado.' },
            { termo: 'Peso no USD', texto: 'Quanto o risco deste mercado conta na exposição ao dólar (1 = tudo; 0 = nada).' },
            { termo: 'Saída por tempo (min)', texto: 'Vazio = a da seção 2. O ouro usa 15: é o horizonte com que a regra dele foi testada.' },
            { termo: 'Stop (ATR) / Alvo (× stop)', texto: 'Vazio = os da seção 3. O ouro usa 1,5 e 1,5, os valores com que a regra deu resultado positivo fora da amostra. O placar e o treino mensal usam os mesmos.' },
          ],
        },
      },

      {
        titulo: 'Barra de salvar e histórico',
        tabela: {
          colunas: ['Elemento', 'Significado'],
          linhas: [
            { termo: 'N mudança(s)', texto: 'O que você alterou e ainda não salvou (linhas alteradas ficam destacadas).' },
            { termo: 'Motivo', texto: 'Texto livre que vai para o histórico.' },
            { termo: 'Descartar / Salvar e aplicar', texto: 'Desfaz ou grava e põe em vigor imediatamente.' },
            { termo: 'Histórico de alterações', texto: 'Quando, origem, motivo e cada valor “antes → depois”.' },
          ],
        },
      },
      {
        titulo: 'Exemplo com o ouro',
        html: `ATR de 15 min = 7,00 → stop = 1,5 × 7,00 = 10,50 dólares de preço. Com 0,01 lote (1 onça), a perda máxima
          é <b>US$ 10,50</b> e o alvo, US$ 15,75. Se o teto por operação for US$ 10, a ordem é recusada (“Risco da ordem”);
          com volatilidade menor (ATR 6,00 → US$ 9,00) ela passa.`,
      },
    ],
  },

  // ======================================================================== modelos
  {
    id: 'modelos',
    titulo: 'Página Modelos',
    resumo: 'Qual versão dos modelos está em uso, o treinamento mensal e os testes honestos (cofre e walk-forward).',
    blocos: [
      { imagem: { src: 'modelos-ouro', legenda: 'Modelos do ouro: versão em produção, treinamento, cofre e walk-forward.' } },
      {
        titulo: 'Em produção',
        tabela: {
          colunas: ['Coluna / botão', 'Significado'],
          linhas: [
            { termo: 'Versão … desde …', texto: 'A versão que gera as previsões ao vivo e quando foi promovida.' },
            { termo: 'Versão', texto: 'Cada treino gera uma versão (data-hora). “em uso” = a atual.' },
            { termo: 'Treinada em / Janela de treino', texto: 'Quando foi treinada e os 24 meses de dados usados.' },
            { termo: 'Contra a versão em uso', texto: 'A versão nova (challenger) comparada com a em uso (champion) no último mês fechado: <b>recomendada</b> ou <b>não recomendada</b> e o motivo. “detalhes por modelo” mostra o log loss e as operações de cada uma.' },
            { termo: 'Promover', texto: 'Põe a versão em uso. As previsões seguintes já usam ela. Decisão sua.' },
          ],
        },
      },
      {
        titulo: 'Treinamento (mensal)',
        html: `O botão <b>Aplicar treinamento (mensal)</b> roda, num processo separado: normalize → features → treino de
          uma versão nova com os últimos 24 meses. Leva de 30 a 50 minutos e o servidor continua funcionando. No fim, a
          versão nova aparece na tabela acima com a comparação, esperando você promover (ou não). O painel mostra a etapa,
          o início, o fim e o log.`,
      },
      {
        titulo: 'Cofre — avaliação única e honesta',
        html: `Os últimos 6 meses de cada mercado ficaram guardados, sem uso em treino nem em comparação, e foram avaliados
          UMA vez. É a melhor estimativa de como o modelo se sai em dados novos. Não há segunda abertura.`,
        tabela: {
          colunas: ['Coluna', 'Significado'],
          linhas: [
            { termo: 'Título (ex.: 15 min · 2026-04 a 2026-09 · N momentos)', texto: 'Horizonte, meses avaliados e quantidade de momentos de decisão.' },
            { termo: 'Log loss (base)', texto: 'Erro das probabilidades; menor é melhor. A “base” só repete a frequência histórica: ganhar dela = o modelo sabe algo.' },
            { termo: 'AUC alta', texto: 'Capacidade de ordenar quem sobe: 0,5 = acaso, 1,0 = perfeito.' },
            { termo: 'Operações gate 4', texto: 'Operações simuladas com o gate 4 (P ≥ 60%, diferença ≥ 35). “nenhuma” é comum.' },
            { termo: 'Com limiar 0,45 / 0,15', texto: 'O mesmo com um gatilho mais frouxo. Formato: operações · E[R] · PF · acerto. Foi aqui que o ouro a 15 min mostrou resultado positivo.' },
          ],
        },
      },
      {
        titulo: 'Walk-forward de referência',
        html: `Treina com 24 meses, testa no mês seguinte, avança um mês e repete: cada mês testado é dado que o modelo
          nunca viu. As colunas são as mesmas do cofre. <b>Como ler:</b> o documento mestre pede E[R] > +0,10 R, profit
          factor > 1,2 e pelo menos 200 operações para considerar operar.`,
      },
    ],
  },

  // ======================================================================== saúde
  {
    id: 'saude',
    titulo: 'Página Saúde',
    resumo: 'O estado de cada parte do sistema (MT5, coletores, Jev, risco) e as tarefas de manutenção.',
    blocos: [
      { imagem: { src: 'saude', legenda: 'Saúde: tarefas no topo e o retrato completo do /api/status (login da conta ocultado na captura).' } },
      {
        titulo: 'Ações',
        tabela: {
          colunas: ['Botão', 'O que faz'],
          linhas: [
            { termo: 'Normalizar dados', texto: 'Reconstrói o silver (candles, calendário, documentos). Roda sozinho às 21:30 UTC; à mão, se as previsões ficarem lentas. ~35 min.' },
            { termo: 'Recalcular features', texto: 'Recalcula o gold (variáveis e resultados) a partir do silver.' },
            { termo: 'Coletar notícias agora', texto: 'Uma coleta imediata dos feeds dos bancos centrais (normalmente a cada minuto).' },
            { termo: 'Atualizar placar', texto: 'Dá o resultado às previsões com horizonte vencido (normalmente a cada 5 min).' },
          ],
        },
        html: 'Uma tarefa por vez, num processo separado. O painel mostra a tarefa, o status (rodando, concluída, falhou), início, fim e o fim do log.',
      },
      {
        titulo: 'Blocos do status',
        tabela: {
          colunas: ['Bloco', 'Significado e o que é saudável'],
          linhas: [
            { termo: 'TIME_UTC / MODE', texto: 'Hora do servidor e modo (SHADOW, DEMO, LIVE).' },
            { termo: 'JEV_MODEL', texto: 'A versão fixa do Jev usada (nunca “latest”).' },
            { termo: 'LAKE_ROOT', texto: 'Onde fica o lake de dados (fora do repositório).' },
            { termo: 'FEEDS_ENABLED', texto: 'Feeds coletados: Fed (discursos, política monetária), BCE, BoE, BoJ, SNB, BoC e RBNZ.' },
            { termo: 'DOCUMENTS_TOTAL / JEV_CALLS_TOTAL', texto: 'Documentos guardados e chamadas ao Jev já feitas (com cache: o mesmo pedido nunca é pago duas vezes).' },
            { termo: 'LATEST_DOCUMENTS', texto: 'Os últimos documentos coletados.' },
            { termo: 'MT5 → inbox_pending / error_files', texto: 'Arquivos esperando importação (saudável: 0 ou poucos) e arquivos com erro (saudável: 0).' },
            { termo: 'MT5 → expected_account / expected_server', texto: 'A conta e o servidor esperados: arquivo de outra conta é recusado.' },
            { termo: 'MT5 → ea_heartbeat', texto: 'Último sinal do EA: age_seconds (saudável: até 30), stale, conta, servidor, trade_mode, ea_mode (EXECUTE = envia ordens), saldo, patrimônio, posições, algo_enabled (AutoTrading ligado no MT5), connected.' },
            { termo: 'MT5 → candles', texto: 'Por símbolo: primeira e última barra, lag_seconds (atraso da última barra; saudável: até ~150 s com mercado aberto), stale, último preço e spread, barras importadas.' },
            { termo: 'MT5 → calendar / next_high_impact', texto: 'Estado do calendário econômico e o próximo evento de importância alta.' },
            { termo: 'RISK_GLOBAL / RISK_MARKETS', texto: 'Os limites em vigor (os mesmos da página Risco).' },
          ],
        },
      },
    ],
  },

  // ======================================================================== modelos e sinais
  {
    id: 'sinais',
    titulo: 'Modelos e sinais',
    resumo: 'O que cada modelo usa, como uma previsão vira sinal e quando o sistema prevê.',
    blocos: [
      {
        titulo: 'Os três modelos',
        tabela: {
          colunas: ['Modelo', 'O que usa'],
          linhas: [
            { termo: 'Preço (A)', texto: 'Só o comportamento do preço: retornos de 15, 60 e 240 min medidos em ATR, volatilidade e regime, RSI, distância da média de 50 horas, amplitude do dia, spread, sessão (Tóquio, Londres, Nova York), hora e dia da semana.' },
            { termo: 'Eventos (E)', texto: 'Só o calendário: surpresa padronizada dos indicadores (atual − previsto), minutos desde o último e até o próximo evento.' },
            { termo: 'Eventos + Preço (B)', texto: 'Os dois juntos. Em todos os testes foi o melhor dos três; é o modelo principal.' },
          ],
        },
        html: 'São modelos de gradient boosting (árvores) treinados com 24 meses, separados por mercado.',
      },
      {
        titulo: 'As três classes',
        html: `Para cada momento, o modelo dá a probabilidade de ${ALTA} (o preço andou para cima mais de <b>meio ATR</b>
          já descontado o spread), ${QUEDA} (o mesmo para baixo) ou LATERAL (nenhum dos dois) dentro do horizonte de 15 ou
          60 minutos. As três somam 100%.`,
      },
      {
        titulo: 'Quando o sistema prevê (momentos de decisão)',
        tabela: {
          colunas: ['Momento', 'Detalhe'],
          linhas: [
            { termo: 'Hora cheia', texto: 'De 00:00 a 20:00 UTC (21:00 a 17:00 em Brasília), segunda a sexta; sexta, só até 18:00 UTC.' },
            { termo: 'Evento + 2 min', texto: '2 minutos depois da divulgação de um indicador de importância média ou alta. Ex.: dados dos EUA às 12:30 UTC → previsão às 12:32 UTC (09:32 em Brasília).' },
            { termo: 'Manual', texto: 'Botão “Prever agora” (fora do placar).' },
          ],
        },
        html: `O ouro é previsto primeiro em cada rodada (cerca de 1 minuto depois do momento). Se uma rodada demora e um
          momento fica para trás, ele é previsto logo em seguida com o horário original.`,
      },
      {
        titulo: 'Do número ao sinal',
        tabela: {
          colunas: ['Critério', 'Regra'],
          linhas: [
            { termo: 'Gate 4 (todos os mercados sem regra própria)', texto: 'P do lado ≥ 60% <b>e</b> diferença ≥ 35 pontos para o lado oposto. É raro de propósito: nos testes, nenhum modelo de forex, índices ou ações teve vantagem que pagasse o custo.' },
            { termo: 'Regra do ouro', texto: 'Modelo Eventos + Preço, 15 min, P ≥ 45% e diferença ≥ 15 pontos, <b>só no momento de uma divulgação</b>. Saídas: stop 1,5 ATR, alvo 1,5 × stop, fecha em 15 min.' },
          ],
        },
      },
      {
        titulo: 'O que se sabe da regra do ouro',
        html: `<ul>
          <li><b>Walk-forward</b> (jan/2024 a mar/2026): 49 operações em divulgações, média <b>+0,23 R</b>.</li>
          <li><b>Cofre</b> (abr a set/2026, avaliado uma vez): 12 operações, <b>+0,38 R</b>.</li>
          <li>Na hora cheia o resultado foi ~0: por isso a regra só vale em evento.</li>
          <li>Cerca de <b>2 sinais por mês</b>, quase sempre nas divulgações dos EUA: 12:32, 13:32 ou 14:02 UTC no horário
            de verão americano (09:32, 10:32 ou 11:02 em Brasília); uma hora depois no inverno americano.</li>
          <li>Entrar 5 minutos atrasado levou o resultado para perto de zero.</li>
          <li>Com 0,01 lote: perda no stop de ~US$ 10–15; pior sequência histórica −3,9 R (~US$ 45–60), com 6 perdas seguidas.</li>
          <li>Poucas operações (61) e ~75% de compras num período de forte alta do ouro: o placar ao vivo é o teste que falta.</li>
        </ul>`,
      },
    ],
  },

  // ======================================================================== passo a passo
  {
    id: 'passo-a-passo',
    titulo: 'Passo a passo: operar o sinal do ouro',
    resumo: 'Do preparo ao fechamento, na ordem.',
    blocos: [
      {
        html: `<ol class="flow">
          <li><b>Antes:</b> servidor no ar (relógio no topo, sem “API fora do ar”), MT5 aberto com o EA no gráfico e
            AutoTrading ligado, página <b>Operar</b> mostrando o EA <i>conectado</i> e o envio LIGADO.</li>
          <li>No topo, escolha <b>Ouro</b>. Deixe a página <b>Hoje</b> aberta e permita as notificações do navegador.</li>
          <li>Confira na página <b>Risco</b> que o mercado Metais está ativo e que o teto por operação comporta a perda do
            stop com 0,01 (veja o exemplo em Risco). Ajuste o <b>Lote por ordem</b> na página Hoje.</li>
          <li>Nos horários das divulgações dos EUA, se houver sinal, aparece o <b>aviso de SINAL</b> com bipe. Confira lado,
            probabilidades e a idade (“há N min”).</li>
          <li>Clique em <b>Operar agora</b>. No quadro, confira a <b>Perda máx.</b> e as <b>Travas</b> (“todas ok”) e clique
            em <b>Enviar</b> — de preferência até ~2 minutos depois do sinal.</li>
          <li>Em <b>Operar → Ordens recentes</b>, a ordem passa de PENDING para FILLED; a posição aparece em
            <b>Posições abertas</b> com SL e TP no servidor.</li>
          <li>A posição fecha sozinha no stop, no alvo ou em <b>15 minutos</b>. O resultado entra em
            <b>Suas operações</b>; o sinal entra no <b>placar</b> da página Previsões.</li>
        </ol>`,
      },
    ],
  },

  // ======================================================================== rotina
  {
    id: 'rotina',
    titulo: 'O que roda sozinho e o que é com você',
    resumo: 'Agenda do sistema (UTC e Brasília) e as tarefas manuais.',
    blocos: [
      {
        tabela: {
          colunas: ['Quando', 'O quê'],
          linhas: [
            { termo: 'A cada 2 s', texto: 'Importa os arquivos do MT5 (barras, calendário, especificações).' },
            { termo: 'A cada ~5 s', texto: 'O EA manda heartbeat (saldo, cotações, posições) e busca ordens.' },
            { termo: 'A cada 15 s', texto: 'Confere saídas por tempo vencidas e manda fechar.' },
            { termo: 'A cada minuto', texto: 'Coleta os feeds dos bancos centrais; confere se é momento de decisão (segundo 20).' },
            { termo: 'Hora cheia e evento + 2 min', texto: 'Previsões ao vivo (seg a sex, 00:00–20:00 UTC).' },
            { termo: 'A cada 5 min', texto: 'Placar: resultado das previsões com horizonte vencido.' },
            { termo: '21:30 UTC (18:30 Brasília), seg a sex', texto: 'Normalize automático (~35 min): mantém as previsões rápidas.' },
            { termo: 'Uma vez por mês (você)', texto: 'Modelos → Aplicar treinamento; depois decidir se promove a versão nova.' },
            { termo: 'Quando quiser (você)', texto: 'Hoje → Jev: ver plano e custo → Jev: avaliar (gasta créditos, teto US$ 0,10).' },
            { termo: 'Depois de mudar o dashboard', texto: 'Atualizar as imagens desta ajuda: <code>node tools/capturar-ajuda.mjs</code> (servidor no ar).' },
          ],
        },
      },
    ],
  },

  // ======================================================================== problemas
  {
    id: 'problemas',
    titulo: 'Problemas comuns',
    resumo: 'O que a tela mostra, a causa provável e o que fazer.',
    blocos: [
      {
        tabela: {
          colunas: ['Sintoma', 'Causa e solução'],
          linhas: [
            { termo: '“API fora do ar” no topo', texto: 'O servidor parou. Na pasta jev-forex (PowerShell): <code>java -jar jev-app\\target\\jev-app.jar --trading.mode=demo</code>.' },
            { termo: 'EA “sem heartbeat”', texto: 'MT5 fechado, EA fora do gráfico ou AutoTrading desligado. Abra o MT5 e confira o EA JevExecutor no gráfico.' },
            { termo: 'Trava “ATR (última previsão)” ✗', texto: 'A última previsão do símbolo tem mais de 90 min (fora das horas de previsão). Clique em Previsões → Prever agora.' },
            { termo: 'Trava “Risco da ordem” ✗', texto: 'A perda do stop com esse lote passa do teto por operação. Diminua o lote (se possível) ou ajuste o teto em Risco (decisão sua).' },
            { termo: 'Trava “Spread (gate 3)” ou “Fora de blackout (gate 2)” ✗', texto: 'Mercado agitado ou evento importante perto. Espere alguns minutos.' },
            { termo: 'Trava “Par livre” ✗', texto: 'Já existe posição nesse símbolo. Feche-a antes ou opere outro.' },
            { termo: '“Nenhum símbolo com dados frescos”', texto: 'Mercado fechado (fim de semana, feriado) ou o MT5 parou de exportar barras. Em Saúde, veja MT5 → candles (lag_seconds).' },
            { termo: 'Ordem EXPIRED', texto: 'O EA não buscou a ordem em 30 s: está desconectado ou com InpExecute = false.' },
            { termo: 'Ordem REJECTED', texto: 'A corretora ou o EA recusou; o motivo está em Detalhe (mercado fechado, lote, desvio de preço…).' },
            { termo: 'Notícia “não avaliado”', texto: 'O Jev ainda não leu o documento. Hoje → Jev: ver plano e custo → Jev: avaliar.' },
            { termo: 'Previsões atrasadas (vários minutos)', texto: 'O bronze recente cresceu (normalize não rodou). Saúde → Normalizar dados.' },
            { termo: 'Imagem desta ajuda não aparece', texto: 'As capturas ficam fora do git. Com o servidor no ar: <code>node tools/capturar-ajuda.mjs</code>.' },
          ],
        },
      },
    ],
  },
];

/** Nome legível de cada chave do glossário (help.ts). */
export const GLOSSARIO_NOMES: Record<string, string> = {
  R: 'R (unidade de risco)', ATR: 'ATR', spread: 'Spread', lote: 'Lote', pip: 'Pip', margem: 'Margem',
  patrimonio: 'Patrimônio (equity)', modeloA: 'Modelo Preço (A)', modeloE: 'Modelo Eventos (E)',
  modeloB: 'Modelo Eventos + Preço (B)', probabilidades: 'Probabilidades', gate4: 'Gate 4', horizonte: 'Horizonte',
  logloss: 'Log loss', auc: 'AUC', er: 'E[R]', pf: 'Profit factor', acerto: 'Acerto', dd: 'Drawdown máximo',
  acertoClasse: 'Acerto da classe', resolvidas: 'Previsões resolvidas', cofre: 'Cofre', walkforward: 'Walk-forward',
  challenger: 'Champion × challenger', evento: 'Momento de evento', jev: 'Leitura do Jev',
  maxRiskPerTradePct: 'Teto por operação (%)', maxRiskPerTradeUsd: 'Teto por operação (US$)',
  maxOpenRiskPct: 'Risco aberto somado', maxDailyLossPct: 'Perda máxima no dia', maxWeeklyLossPct: 'Perda máxima na semana',
  maxPositions: 'Posições simultâneas', maxUsdNetExposurePct: 'Exposição líquida ao dólar', minLotPolicy: 'Política do lote mínimo',
  defaultLot: 'Lote padrão', maxLot: 'Lote máximo', closeAfterMinutes: 'Saída por tempo', deviationPoints: 'Desvio máximo',
  validitySeconds: 'Validade da ordem', quoteMaxAgeSeconds: 'Idade máxima da cotação', atrMaxAgeMinutes: 'Idade máxima do ATR',
  blackoutBeforeMinutes: 'Blackout antes do evento', blackoutAfterMinutes: 'Blackout depois do evento',
  maxSpreadRatio: 'Spread máximo', minMarginLevelPct: 'Nível de margem mínimo', sessionMarginMinutes: 'Folga antes do fim da sessão', stopAtr: 'Stop (× ATR)', targetR: 'Alvo (× stop)',
  marketEnabled: 'Mercado ativo', riskPerTradePct: 'Risco-alvo por operação', marketMaxOpenRiskPct: 'Risco aberto do mercado',
  marketMaxPositions: 'Posições do mercado', marketCloseAfter: 'Saída por tempo do mercado', marketStopAtr: 'Stop do mercado',
  marketTargetR: 'Alvo do mercado', regraOuro: 'Regra de sinal do mercado', usdWeight: 'Peso no USD', lossProfile: 'Trava de perda',
};
