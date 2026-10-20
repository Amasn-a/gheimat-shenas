// Production builds of the player (dist/player) and the standalone admin (dist/admin), then a bundle audit:
// no development host, reviewer controls, fake-provider routes, dev tokens or test hooks may reach dist/.
import { build } from 'esbuild';
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

const ROOT = new URL('..', import.meta.url).pathname;
const DIST = join(ROOT, 'dist');
const checkOnly = process.argv.includes('--check-only');

async function bundle(app, entries, css) {
  const out = join(DIST, app);
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  const r = await build({ entryPoints: entries, bundle: true, minify: true, format: 'esm', target: ['es2021'],   // shortcut: WebView floor unknown (host docs); es2021 ≈ Chrome 85 / Safari 14.1. Narrow when the floor is known,
    outdir: out, entryNames: '[name]-[hash]', metafile: true, legalComments: 'none', logLevel: 'warning' });
  const c = await build({ stdin: { contents: css.map(f => `@import "${f}";`).join('\n'), resolveDir: join(ROOT, app), loader: 'css' },
    bundle: true, minify: true, outfile: join(out, 'app.css'), external: ['*.woff2'], logLevel: 'warning', write: false });
  const cssText = c.outputFiles[0].text.replaceAll('../fonts/', 'fonts/');   // the bundle sits next to fonts/, not in styles/
  const cssName = `app-${createHash('sha256').update(cssText).digest('hex').slice(0, 8)}.css`;
  writeFileSync(join(out, cssName), cssText);
  cpSync(join(ROOT, app, 'fonts'), join(out, 'fonts'), { recursive: true });
  return { out, js: Object.keys(r.metafile.outputs).map(p => p.split('/').pop()), cssName, meta: r.metafile };
}

function audit() {
  const FORBIDDEN = [/\/dev\/v1/, /fake-coin/i, /reviewer/i, /dev\.html/, /Bearer dev\./, /dvCharge|dvBal/, /\boracle\b/, /editor-dev-password|viewer-dev-password/,
    /localhost|127\.0\.0\.1/, /sourceMappingURL/];
  const bad = [];
  const walk = d => readdirSync(d).forEach(f => {
    const p = join(d, f);
    if (statSync(p).isDirectory()) { if (f === 'dev') bad.push(`${p}: dev directory`); return walk(p); }
    if (!/\.(js|css|html)$/.test(f)) return;
    const s = readFileSync(p, 'utf8');
    FORBIDDEN.forEach(re => { if (re.test(s)) bad.push(`${p}: ${re}`); });
  });
  walk(DIST);
  return bad;
}

if (!checkOnly) {
  // player: the integration-owned host-boot.js is the entry, so its configureHost() call and the game share one host.js
  const p = await bundle('player', [join(ROOT, 'player/src/host-boot.js')], ['./styles/fonts.css', './styles/tokens.css', './styles/ds.css', './styles/app.css']);
  const entry = p.js.find(f => f.startsWith('host-boot-') && f.endsWith('.js'));
  const inputs = Object.values(p.meta.outputs).flatMap(o => Object.keys(o.inputs));
  const missing = ['player/src/host-boot.js', 'player/src/host.js', 'player/src/main.js'].filter(f => !inputs.some(i => i.endsWith(f)));
  if (!entry || missing.length) { console.error('player bundle must contain the host boot, the host adapter and the game; missing: ' + missing.join(', ')); process.exit(1); }
  const pIndex = readFileSync(join(ROOT, 'player/index.html'), 'utf8')
    .replace(/\s*<link rel="stylesheet" href="styles\/[^"]+">/g, '')
    .replace('</head>', `<link rel="stylesheet" href="${p.cssName}">\n</head>`)
    .replace('src="src/host-boot.js"', `src="${entry}"`);
  if ((pIndex.match(/<script\b/g) || []).length !== 1 || !pIndex.includes(`src="${entry}"`)) { console.error('player index.html must load exactly the bundled entry'); process.exit(1); }
  writeFileSync(join(p.out, 'index.html'), pIndex);

  const a = await bundle('admin', [join(ROOT, 'admin/src/app.js')], ['./styles/fonts.css', './styles/kit.css', './styles/admin.css']);
  const aIndex = readFileSync(join(ROOT, 'admin/index.html'), 'utf8')
    .replace(/\s*<link rel="stylesheet" href="styles\/[^"]+">/g, '')
    .replace('</head>', `<link rel="stylesheet" href="${a.cssName}">\n</head>`)
    .replace('src="src/app.js"', `src="${a.js.find(f => f.startsWith('app-'))}"`);
  writeFileSync(join(a.out, 'index.html'), aIndex);
  for (const d of [p.out, a.out]) console.log(d, readdirSync(d).map(f => `${f}${statSync(join(d, f)).isFile() ? ` (${(statSync(join(d, f)).size / 1024).toFixed(1)} KB)` : '/'}`).join(', '));
}
const bad = audit();
if (bad.length) { console.error('bundle audit FAILED:\n' + bad.join('\n')); process.exit(1); }
console.log('bundle audit passed: no dev host, reviewer, fake routes, dev credentials, local hosts or source maps in dist/');
