/**
 * Glossário do dashboard: as explicações que aparecem ao passar o mouse no "?".
 * Uma frase de definição + o que isso significa na prática para a sua conta.
 */
export const HELP: Record<string, string> = {
  // ------------------------------------------------------------------ conceitos gerais
  R: 'R = unidade de risco de uma operação: 1 R é o quanto você perde se o stop for atingido. Ganhar 1,5 R = ganhar 1,5 vez o que arriscou.',
  ATR: 'ATR (Average True Range) = tamanho médio de uma barra de 15 minutos nas últimas 14 barras. Mede a volatilidade: o stop e o alvo são múltiplos dele.',
  spread: 'Spread = diferença entre o preço de compra (ask) e o de venda (bid). É o custo que você paga ao entrar; quanto maior em relação ao ATR, mais difícil lucrar.',
  lote: 'Lote = tamanho da posição. No forex, 1,00 lote = 100.000 da moeda base; 0,01 lote (o mínimo) = 1.000. No EURUSD, 0,01 lote ≈ US$ 0,10 por pip.',
  pip: 'Pip = 0,0001 na maioria dos pares (0,01 nos pares com JPY). Com 0,01 lote no EURUSD, 1 pip ≈ US$ 0,10.',
  margem: 'Margem = dinheiro que a corretora separa para manter as posições abertas. Nível de margem = patrimônio ÷ margem × 100%. Abaixo do stop-out da corretora, ela fecha posições sozinha.',
  patrimonio: 'Patrimônio (equity) = saldo + resultado das posições abertas. É o que você teria se fechasse tudo agora.',

  // ------------------------------------------------------------------ modelos e métricas
  modeloA: 'Preço: usa só o comportamento do preço (retornos, volatilidade, RSI, distância da média, spread, sessão).',
  modeloE: 'Eventos: usa só o calendário econômico (surpresa dos indicadores, tempo desde/até o próximo evento).',
  modeloB: 'Eventos + Preço: junta os dois. Em todos os testes foi o melhor dos três, mas sem vantagem que pague o custo.',
  probabilidades: 'Probabilidade de cada resultado nos próximos minutos: QUEDA (vermelho), LATERAL (cinza: não andou meio ATR) e ALTA (verde). As três somam 100%.',
  gate4: 'Gate 4 = só vira sinal quando a probabilidade do lado é ≥ 60% E supera a do lado oposto em ≥ 35 pontos. É raro de propósito.',
  horizonte: 'Horizonte = por quanto tempo a operação fica aberta no máximo (15 ou 60 min). Se nem o stop nem o alvo forem atingidos, ela fecha no fim do horizonte.',
  logloss: 'Log loss = erro das probabilidades. Menor é melhor; "base" é o modelo que só repete a frequência histórica. Ganhar da base = o modelo sabe algo.',
  auc: 'AUC = capacidade de ordenar: 0,5 é acaso, 1,0 é perfeito. 0,65 = em 65% dos pares (um que subiu, um que não) o modelo deu nota maior ao que subiu.',
  er: 'E[R] = resultado médio por operação, em R, já com o spread. Positivo = ganhou na média. O documento mestre pede > +0,10 R para considerar operar.',
  pf: 'Profit factor = soma dos ganhos ÷ soma das perdas. Acima de 1 ganhou; o documento mestre pede > 1,2.',
  acerto: 'Acerto = fração das operações que terminaram com lucro. Com alvo de 1,5 R, acertar 40% já empata (antes do custo).',
  dd: 'Drawdown máximo = a pior queda acumulada, do topo ao fundo, simulando 0,5% de risco por operação.',
  acertoClasse: 'A classe mais provável (alta, queda ou lateral) bateu com o que aconteceu de fato. Lateral é o mais comum, então esse número sozinho engana.',
  resolvidas: 'Previsões cujo horizonte já passou e ganharam o resultado real (o placar roda a cada 5 minutos).',
  cofre: 'Cofre = os últimos 6 meses guardados sem uso no treino e nos testes, avaliados UMA vez. É a estimativa mais honesta de como o modelo se sai em dados novos.',
  walkforward: 'Walk-forward = treina com 24 meses, testa no mês seguinte, avança um mês e repete. Cada mês testado é dado que o modelo nunca viu.',
  challenger: 'A versão nova (challenger) é comparada com a em uso (champion) no último mês fechado. Só vale se a em uso não tiver visto esse mês no treino.',
  evento: 'Momento de evento = 2 minutos depois da divulgação de um indicador de importância média ou alta da moeda.',
  jev: 'Leitura do Jev: tom do documento do banco central. Hawkish = sinaliza juros mais altos (tende a fortalecer a moeda); dovish = juros mais baixos.',

  // ------------------------------------------------------------------ risco: conta inteira (global)
  maxRiskPerTradePct: 'Teto de perda de UMA operação, em % do saldo, se o stop for atingido. Vale junto com o teto em US$: o menor dos dois manda.',
  maxRiskPerTradeUsd: 'O mesmo teto em dinheiro. 0 = sem teto em dinheiro (só o %). Útil com banca pequena: impede que o lote mínimo arrisque demais.',
  maxOpenRiskPct: 'Soma do risco (até o stop) de TODAS as posições abertas, em % do saldo. Limita o estrago se todas derem stop ao mesmo tempo.',
  maxDailyLossPct: 'Perda no dia (desde 00:00 UTC) que bloqueia entradas novas até a meia-noite UTC. É o limite do perfil Padrão.',
  maxWeeklyLossPct: 'Perda na semana (desde segunda 00:00 UTC) que bloqueia entradas novas até a próxima segunda. É o limite do perfil Padrão.',
  maxPositions: 'Quantas posições podem estar abertas ao mesmo tempo, somando todos os mercados.',
  maxUsdNetExposurePct: 'Exposição líquida ao dólar no forex: comprar EURUSD é vender USD; comprar USDJPY é comprar USD. Soma o risco nessa direção, em % do saldo.',
  minLotPolicy: 'Se nem o lote mínimo (0,01) cabe no risco-alvo: SKIP = não opera; ALLOW_UP_TO_CAP = opera com 0,01 desde que não passe do teto por operação.',

  // ------------------------------------------------------------------ risco: cada ordem
  defaultLot: 'Lote que o tíquete da página Operar já traz preenchido.',
  maxLot: 'Maior lote aceito numa ordem, peça quem pedir. O EA também tem o próprio teto (InpMaxLot) — vale o menor.',
  closeAfterMinutes: 'Saída por tempo: a posição é fechada depois de N minutos se o stop e o alvo não forem atingidos. 0 = só stop/alvo. 60 = o horizonte do modelo principal.',
  deviationPoints: 'Desvio máximo aceito entre o preço pedido e o executado, em pontos (10 pontos = 1 pip no EURUSD). Acima disso a corretora recusa.',
  validitySeconds: 'Validade da ordem: o EA recusa uma ordem mais velha que isso (o preço do clique já ficou velho).',
  quoteMaxAgeSeconds: 'A cotação mandada pelo EA não pode ser mais velha que isso; senão não há ordem (falhar fechado).',
  atrMaxAgeMinutes: 'O ATR usado para o stop vem da última previsão; mais velho que isso = sem ordem (clique em "Prever agora").',
  blackoutBeforeMinutes: 'Gate 2: sem entrada nova nos N minutos ANTES de um evento de importância alta da moeda (o preço salta e o spread abre).',
  blackoutAfterMinutes: 'Gate 2: … nem nos N minutos DEPOIS do evento.',
  maxSpreadRatio: 'Gate 3: o spread atual pode ser no máximo N vezes o spread típico daquele horário (mediana dos últimos 20 dias).',
  minMarginLevelPct: 'Sem entrada nova se o nível de margem (patrimônio ÷ margem) estiver abaixo disso. 300% = folga grande antes do stop-out.',

  // ------------------------------------------------------------------ risco: saídas e mercados
  stopAtr: 'Distância do stop em múltiplos do ATR. 1,5 ATR = 1 R. Stop maior = menos stops por ruído, mas perda maior em US$ com o mesmo lote.',
  targetR: 'Alvo em múltiplos do stop. 1,5 = o alvo fica 1,5 vez mais longe que o stop (ganha 1,5 R ou perde 1 R).',
  marketEnabled: 'Se o mercado pode receber ordens.',
  riskPerTradePct: 'Risco-ALVO por operação neste mercado (o tamanho ideal). Com banca pequena, o lote mínimo costuma passar disso — aí manda a política de lote mínimo.',
  marketMaxOpenRiskPct: 'Soma do risco das posições abertas SÓ deste mercado, em % do saldo.',
  marketMaxPositions: 'Posições abertas ao mesmo tempo SÓ neste mercado.',
  marketCloseAfter: 'Fecha a posição deste mercado depois de N minutos, se o stop ou o alvo não vierem antes. Vazio = a saída por tempo das travas de ordem; 0 = só SL/TP. O ouro usa 15: é o horizonte com que a regra dele foi testada.',
  marketStopAtr: 'Stop deste mercado em múltiplos do ATR (volatilidade média de 15 min). Vazio = o stop da seção Saídas. O ouro usa 1,5: foi com ele que a regra do ouro deu resultado positivo fora da amostra.',
  marketTargetR: 'Alvo deste mercado em múltiplos do stop. Vazio = o alvo da seção Saídas. O ouro usa 1,5.',
  regraOuro: 'Regra validada fora da amostra só para o ouro: modelo Eventos + Preço a 15 min, P ≥ 45% e diferença ≥ 15 pontos, apenas no momento de uma divulgação (evento + 2 min). A ordem sai sozinha em 15 min.',
  usdWeight: 'Quanto o risco deste mercado conta na exposição a USD (1 = tudo; 0 = nada, como índices e ações).',
  lossProfile: 'Perfil de perda: troca só os limites de perda do dia e da semana. Escolha o perfil na página Operar.',
};
