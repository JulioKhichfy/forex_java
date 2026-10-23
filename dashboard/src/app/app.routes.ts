import { Routes } from '@angular/router';
import { Hoje } from './pages/hoje';
import { Previsoes } from './pages/previsoes';
import { Modelos } from './pages/modelos';
import { Saude } from './pages/saude';
import { Operar } from './pages/operar';
import { Risco } from './pages/risco';
import { Ajuda } from './pages/ajuda';
import { Radar } from './pages/radar';
import { Roleta } from './pages/roleta';
import { StopDolar } from './pages/stop-dolar';
import { Laboratorio } from './pages/laboratorio';
import { Eventos } from './pages/eventos';
import { Cassino } from './pages/cassino';
import { Universo } from './pages/universo';

export const routes: Routes = [
  { path: '', component: Hoje, title: 'Hoje · Jev Forex' },
  { path: 'radar', component: Radar, title: 'Radar · Jev Forex' },
  { path: 'previsoes', component: Previsoes, title: 'Previsões · Jev Forex' },
  { path: 'eventos', component: Eventos, title: 'Eventos · Jev Forex' },
  { path: 'universo', component: Universo, title: 'Universo · Jev Forex' },
  { path: 'operar', component: Operar, title: 'Operar · Jev Forex' },
  { path: 'roleta', component: Roleta, title: 'Roleta russa · Jev Forex' },
  { path: 'cassino', component: Cassino, title: 'Cassino · Jev Forex' },
  { path: 'alta-frequencia', redirectTo: 'cassino' },
  { path: 'stop-dolar', component: StopDolar, title: 'Stop em US$ · Jev Forex' },
  { path: 'risco', component: Risco, title: 'Risco · Jev Forex' },
  { path: 'laboratorio', component: Laboratorio, title: 'Laboratório · Jev Forex' },
  { path: 'modelos', component: Modelos, title: 'Modelos · Jev Forex' },
  { path: 'saude', component: Saude, title: 'Saúde · Jev Forex' },
  { path: 'ajuda', component: Ajuda, title: 'Ajuda · Jev Forex' },
  { path: '**', redirectTo: '' },
];
