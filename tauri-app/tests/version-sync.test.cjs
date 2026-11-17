const { test } = require('node:test');
const assert = require('node:assert/strict');

test('version sync keeps stable build metadata and dotted prereleases', async () => {
  const { toSemver } = await import('../sync-version.js');
  for (const version of ['2.2.0', '2.2.0+local.1', '2.0.0-alpha.6', '2.2.0-rc.1+local.1']) {
    assert.equal(toSemver(version), version);
  }
  assert.equal(toSemver('2.0.0 alpha 6'), '2.0.0-alpha6');
});
