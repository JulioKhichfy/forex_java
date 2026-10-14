// Fotografa as páginas do dashboard para a aba Ajuda (Chrome headless + protocolo DevTools, sem dependências).
//
//   node tools/capturar-ajuda.mjs            (com o servidor no ar em http://127.0.0.1:8080)
//
// As imagens vão para dashboard/public/ajuda (próximo build) e dashboard/dist/.../ajuda (já aparecem).
// Ficam FORA do git: mostram saldo, ordens e posições da sua conta, e o repositório é público.
// Antes de cada foto, números longos (login da conta, tíquetes) viram ••••••••.
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = process.env.JEV_DASHBOARD ?? 'http://127.0.0.1:8080';
const OUT = [join(ROOT, 'dashboard/public/ajuda'), join(ROOT, 'dashboard/dist/jev-dashboard/browser/ajuda')];
const WIDTH = 1440;
const PORT = 9333;
const CHROME = [
  process.env.CHROME,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find((p) => p && existsSync(p));

// nome do arquivo · rota · mercado · ação antes da foto · recorte (seletor CSS; vazio = página inteira)
const SHOTS = [
  { name: 'topo', route: '/', market: 'fx', clip: 'header.top' },
  { name: 'hoje-forex', route: '/', market: 'fx' },
  { name: 'hoje-ouro', route: '/', market: 'metals' },
  { name: 'radar', route: '/radar', market: 'fx' },
  { name: 'previsoes', route: '/previsoes', market: 'fx', click: 'tr.row' },
  { name: 'operar-ouro', route: '/operar', market: 'metals', clickText: 'Calcular COMPRA' },
  { name: 'operar-travas', route: '/operar', market: 'metals', clickText: 'Calcular COMPRA', clip: '.preview' },
  { name: 'risco', route: '/risco', market: 'fx' },
  { name: 'risco-mercados', route: '/risco', market: 'fx', clipText: '4 · Orçamento por mercado' },
  { name: 'modelos-ouro', route: '/modelos', market: 'metals' },
  { name: 'saude', route: '/saude', market: 'fx' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!CHROME) throw new Error('Chrome ou Edge não encontrado (defina CHROME=caminho do executável)');
  const ok = await fetch(BASE + '/api/status').then((r) => r.ok).catch(() => false);
  if (!ok) throw new Error(`O servidor não respondeu em ${BASE}: suba o jev-app antes`);
  OUT.forEach((d) => mkdirSync(d, { recursive: true }));

  const profile = join(tmpdir(), 'jev-ajuda-chrome');
  rmSync(profile, { recursive: true, force: true });
  const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--hide-scrollbars', `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`, `--window-size=${WIDTH},900`, '--no-first-run', 'about:blank'], { stdio: 'ignore' });
  try {
    const target = await waitTarget();
    const cdp = await connect(target.webSocketDebuggerUrl);
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');
    for (const s of SHOTS) {
      await shoot(cdp, s);
      console.log('  ✓ ' + s.name + '.png');
    }
    cdp.close();
  } finally {
    chrome.kill();
  }
  console.log(`Imagens em ${OUT[0]} (e já no dashboard em uso). Recarregue a aba Ajuda.`);
}

async function shoot(cdp, s) {
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 900, deviceScaleFactor: 1, mobile: false });
  // o mercado vem do navegador (localStorage); uma query nova força recarregar a página com ele
  await navigate(cdp, BASE + '/');
  await evaluate(cdp, `localStorage.setItem('jev.market', ${JSON.stringify(s.market)})`);
  await navigate(cdp, `${BASE}/?f=${Date.now()}#${s.route}`);
  await waitQuiet(cdp);
  if (s.click) await evaluate(cdp, `document.querySelector(${JSON.stringify(s.click)})?.click()`);
  if (s.clickText) {
    await evaluate(cdp, `[...document.querySelectorAll('button')]
      .find(b => b.textContent.trim() === ${JSON.stringify(s.clickText)})?.click()`);
  }
  if (s.click || s.clickText) await sleep(2500);
  await evaluate(cdp, MASK);
  const height = await evaluate(cdp, 'Math.ceil(document.documentElement.scrollHeight)');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height, deviceScaleFactor: 1, mobile: false });
  await sleep(400);
  let clip;
  const sel = s.clip ? `document.querySelector(${JSON.stringify(s.clip)})`
    : s.clipText ? `[...document.querySelectorAll('section.panel')].find(e => e.querySelector('h3')?.textContent.includes(${JSON.stringify(s.clipText)}))`
    : null;
  if (sel) {
    const r = await evaluate(cdp, `(() => { const e = ${sel}; if (!e) return null; const b = e.getBoundingClientRect();
      return { x: Math.max(0, b.x - 8), y: Math.max(0, b.y + scrollY - 8), width: Math.min(${WIDTH}, b.width + 16), height: b.height + 16 }; })()`);
    if (r) clip = { ...r, scale: 1 };
  }
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true,
    clip: clip ?? { x: 0, y: 0, width: WIDTH, height, scale: 1 } });
  for (const d of OUT) writeFileSync(join(d, s.name + '.png'), Buffer.from(shot.data, 'base64'));
}

// login da conta, tíquetes e afins: qualquer número inteiro com 8 dígitos ou mais
const MASK = `(() => {
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n = w.nextNode(); n; n = w.nextNode()) {
    if (/\\d{8,}/.test(n.nodeValue)) n.nodeValue = n.nodeValue.replace(/(?<![\\d.,])\\d{8,}(?![\\d.,]\\d)/g, '••••••••');
  }
})()`;

async function waitQuiet(cdp) {
  // espera o Angular carregar os dados: sem "Carregando…" e o tamanho da página estável
  let last = -1;
  for (let i = 0; i < 40; i++) {
    await sleep(500);
    const st = await evaluate(cdp, `({ loading: document.body.innerText.includes('Carregando…'), h: document.documentElement.scrollHeight })`);
    if (!st.loading && st.h === last && i > 4) return;
    last = st.h;
  }
}

async function navigate(cdp, url) {
  const loaded = cdp.once('Page.loadEventFired');
  await cdp.send('Page.navigate', { url });
  await Promise.race([loaded, sleep(15000)]);
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  return r.result?.value;
}

async function waitTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await fetch(`http://127.0.0.1:${PORT}/json/list`).then((r) => r.json());
      const page = list.find((t) => t.type === 'page');
      if (page) return page;
    } catch { /* Chrome ainda subindo */ }
    await sleep(250);
  }
  throw new Error('O Chrome headless não abriu a porta de depuração');
}

function connect(url) {
  const ws = new WebSocket(url);
  let id = 0;
  const pending = new Map();
  const waiters = new Map();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id);
      pending.delete(m.id);
      m.error ? reject(new Error(m.error.message)) : resolve(m.result);
    } else if (m.method && waiters.has(m.method)) {
      waiters.get(m.method).forEach((f) => f(m.params));
      waiters.delete(m.method);
    }
  };
  const api = {
    send: (method, params = {}) => new Promise((resolve, reject) => {
      pending.set(++id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    }),
    once: (method) => new Promise((resolve) => waiters.set(method, [...(waiters.get(method) ?? []), resolve])),
    close: () => ws.close(),
  };
  return new Promise((resolve, reject) => { ws.onopen = () => resolve(api); ws.onerror = reject; });
}

main().catch((e) => { console.error('Erro: ' + e.message); process.exit(1); });
