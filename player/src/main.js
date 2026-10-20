// Game start. Loaded by host-boot.js (production entry, bundled with it) or by dev/reviewer.js (development host), each
// after configureHost(). No reviewer controls, fake data or test hooks are part of this bundle.
// Light theme only (brief 2026-10-20): no system preference, host theme or stored setting can switch to dark.
import { init } from './game.js';

document.documentElement.setAttribute('data-theme', 'light');
init(document.getElementById('app'));
