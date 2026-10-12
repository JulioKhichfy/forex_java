import { Routes } from '@angular/router';
import { Hoje } from './pages/hoje';
import { Previsoes } from './pages/previsoes';
import { Modelos } from './pages/modelos';
import { Saude } from './pages/saude';
import { Operar } from './pages/operar';
import { Risco } from './pages/risco';

export const routes: Routes = [
  { path: '', component: Hoje, title: 'Hoje · Jev Forex' },
  { path: 'previsoes', component: Previsoes, title: 'Previsões · Jev Forex' },
  { path: 'operar', component: Operar, title: 'Operar · Jev Forex' },
  { path: 'risco', component: Risco, title: 'Risco · Jev Forex' },
  { path: 'modelos', component: Modelos, title: 'Modelos · Jev Forex' },
  { path: 'saude', component: Saude, title: 'Saúde · Jev Forex' },
  { path: '**', redirectTo: '' },
];
