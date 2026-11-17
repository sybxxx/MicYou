const assert = require('node:assert/strict');
const { test } = require('node:test');
const { pathToFileURL } = require('node:url');
const { join } = require('node:path');

const source = import(pathToFileURL(join(__dirname, '../src/features/connection/utils/connectionNotifications.ts')));

test('rapid reconnect updates state without a disconnect/connect notification pair', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { connectionNotifications } = await source;
  const messages = [];
  const alerts = connectionNotifications((body) => messages.push(body));
  alerts.connected('connected');
  alerts.disconnected('disconnected');
  t.mock.timers.tick(2900);
  alerts.connected('connected again');
  t.mock.timers.tick(3100);
  assert.deepEqual(messages, ['connected']);
});

test('a sustained disconnect is reported once and a later reconnect is reported', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { connectionNotifications } = await source;
  const messages = [];
  const alerts = connectionNotifications((body) => messages.push(body));
  alerts.connected('connected');
  alerts.connected('duplicate');
  alerts.disconnected('disconnected');
  alerts.disconnected('duplicate');
  t.mock.timers.tick(2999);
  assert.deepEqual(messages, ['connected']);
  t.mock.timers.tick(1);
  alerts.connected('reconnected');
  assert.deepEqual(messages, ['connected', 'disconnected', 'reconnected']);
});

test('manual stop cancels delayed alerts and reload restores an existing session quietly', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { connectionNotifications } = await source;
  const messages = [];
  const alerts = connectionNotifications((body) => messages.push(body));
  alerts.restore(true);
  alerts.connected('already connected');
  assert.deepEqual(messages, []);
  alerts.disconnected('disconnected');
  alerts.reset();
  t.mock.timers.tick(3000);
  assert.deepEqual(messages, []);
  alerts.connected('new session');
  assert.deepEqual(messages, ['new session']);
});
