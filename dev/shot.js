const { chromium } = require('playwright');
(async () => {
  const [html, out, scale] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: Number(scale) || 1, viewport: { width: 400, height: 300 } });
  await p.goto('file://' + html);
  await p.locator('pre').screenshot({ path: out });
  await b.close();
})();
