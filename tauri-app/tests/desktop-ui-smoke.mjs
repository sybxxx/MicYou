// Run against a separately served, already-built dist directory. This check
// opens only its own browser and injects an in-memory Tauri implementation.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const here = dirname(fileURLToPath(import.meta.url));
const output = resolve(here, '../output/playwright/desktop-migration');
const baseUrl = process.env.MICYOU_UI_URL ?? 'http://127.0.0.1:1431';
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const errors = [];
try {
  const context = await browser.newContext({ viewport: { width: 1000, height: 760 }, colorScheme: 'dark', locale: 'zh-CN' });
  await context.addInitScript({ path: resolve(here, 'fixtures/desktop-tauri-mock.js') });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(baseUrl);
  await page.getByText('等待设备连接...', { exact: true }).waitFor();
  await page.evaluate(() => window.__MICYOU_MOCK__.emit('audio-metrics', { bitrate: 32000, sampleRate: 48000, latencyMs: 30, networkLatencyMs: 20, packetLossRate: 0, jitterMs: 2, bufferDurationMs: 10 }));
  assert.equal(await page.getByText('正在接收音频', { exact: true }).count(), 0);

  await page.evaluate(() => window.__MICYOU_MOCK__.emit('pairing-requested', { requestId: 'ui-test', deviceName: 'Test phone', sas: '123456' }));
  const dialog = page.getByRole('dialog', { name: '配对请求' });
  await dialog.waitFor();
  assert.equal((await page.getByTestId('pairing-sas').textContent()).trim(), '123456');
  await page.screenshot({ path: resolve(output, 'pairing.png') });
  await page.setViewportSize({ width: 720, height: 560 });
  await page.screenshot({ path: resolve(output, 'pairing-minimum.png') });
  await dialog.getByRole('button', { name: '允许', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  const verdict = await page.evaluate(() => window.__MICYOU_MOCK__.calls.find((item) => item.command === 'resolve_pairing'));
  assert.deepEqual(verdict.args, { requestId: 'ui-test', accept: true });
  await page.setViewportSize({ width: 1000, height: 760 });

  await page.evaluate(() => {
    window.__MICYOU_MOCK__.emit('device-connected', { name: 'Test phone', ip: '192.0.2.20', latency: 20, platform: 'android' });
    window.__MICYOU_MOCK__.emit('session-security', { encrypted: false });
  });
  await page.getByText('当前会话未加密', { exact: true }).waitFor();
  await page.screenshot({ path: resolve(output, 'unencrypted.png') });
  await page.evaluate(() => window.__MICYOU_MOCK__.emit('session-security', { encrypted: true }));
  await page.getByText('当前会话已加密', { exact: true }).waitFor();
  await page.screenshot({ path: resolve(output, 'encrypted.png') });

  const settings = await context.newPage();
  settings.setDefaultTimeout(15000);
  settings.on('pageerror', (error) => errors.push(error.message));
  await settings.goto(`${baseUrl}/#/settings`);
  const heading = settings.getByRole('heading', { name: '要求 Wi-Fi 客户端加密连接', exact: true });
  await heading.scrollIntoViewIfNeeded();
  const toggle = heading.locator('..').locator('..').getByRole('switch');
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  await settings.screenshot({ path: resolve(output, 'settings.png') });
  await toggle.click();
  await page.waitForFunction(() => window.__MICYOU_MOCK__.prefs.requireEncryption === false);
  assert.deepEqual(errors, []);
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ passed: true, checks: ['metrics do not imply audio', 'pairing code and verdict', 'encrypted/plaintext badges', 'cross-window encryption setting'], pageErrors: errors }, null, 2));
  console.log(`Desktop UI smoke passed; screenshots: ${output}`);
} finally {
  await browser.close();
}
