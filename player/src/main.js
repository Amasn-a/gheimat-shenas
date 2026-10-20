// Game start. Loaded by host-boot.js (production entry, bundled with it) or by dev/reviewer.js (development host), each
// after configureHost(). No reviewer controls, fake data or test hooks are part of this bundle.
import { init } from './game.js';
import * as host from './host.js';

function applyTheme() { document.documentElement.setAttribute('data-theme', host.theme()); }
applyTheme();
host.onTheme(applyTheme);
init(document.getElementById('app'));
