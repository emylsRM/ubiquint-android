// Genera il progetto Android TWA da twa-manifest.json senza prompt interattivi.
const { TwaManifest, TwaGenerator, ConsoleLog } = require('@bubblewrap/core');

(async () => {
  const m = await TwaManifest.fromFile('twa-manifest.json');
  // Icone servite localmente dal workflow (provvisorie)
  m.iconUrl = 'http://localhost:8080/icon-512.png';
  m.maskableIconUrl = 'http://localhost:8080/maskable-512.png';
  const vc = parseInt(process.env.VERSION_CODE || '1', 10);
  m.appVersionCode = vc;
  m.appVersionName = String(vc);
  m.signingKey = { path: process.env.KS_PATH, alias: process.env.KEY_ALIAS || 'ubiquint' };
  await new TwaGenerator().createTwaProject('android', m, new ConsoleLog('twa'));
  console.log('Progetto Android generato in ./android');
})().catch(e => { console.error(e); process.exit(1); });
