// Entry point. The host integration must call configureHost() before this module runs (production) — dev.html does it
// with a clearly labelled development host. No reviewer controls, fake data or test hooks are part of this bundle.
import { init } from './game.js';
import * as host from './host.js';

function applyTheme() { document.documentElement.setAttribute('data-theme', host.theme()); }
applyTheme();
host.onTheme(applyTheme);
init(document.getElementById('app'));
