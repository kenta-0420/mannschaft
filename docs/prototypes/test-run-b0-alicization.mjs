#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./run-b0-alicization.mjs', import.meta.url));
const root = path.resolve(path.dirname(script), '../..');
const list = spawnSync(process.execPath, [script, 'list'], { cwd: root, encoding: 'utf8' });
assert.equal(list.status, 0);
assert.equal(list.stdout.trim().split(/\r?\n/).length, 10);
const dryRun = spawnSync(process.execPath, [script, 'dry-run', 'B0-J7'], { cwd: root, encoding: 'utf8' });
assert.equal(dryRun.status, 0);
assert.match(dryRun.stdout, /^B0-J7\t/);
const fixtures = spawnSync(process.execPath, [script, '--self-test-fixture'], { cwd: root, encoding: 'utf8' });
assert.equal(fixtures.status, 0);
assert.match(fixtures.stdout, /fixture self-test: ok/);
console.log('run-b0-alicization self-test: ok');
