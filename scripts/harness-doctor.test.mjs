import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import test from 'node:test';

const execFileAsync = promisify(execFile);
const script = fileURLToPath(new URL('./harness-doctor.mjs', import.meta.url));

async function git(cwd, args) {
  return execFileAsync('git', args, { cwd });
}

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'harness-doctor-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await git(root, ['init', '-b', 'main']);
  await git(root, ['config', 'core.hooksPath', join(root, '.git', 'hooks')]);
  await git(root, ['config', 'user.email', 'doctor@example.test']);
  await git(root, ['config', 'user.name', 'doctor test']);
  await mkdir(join(root, '.githooks'), { recursive: true });
  await mkdir(join(root, '.claude', 'hooks'), { recursive: true });
  await mkdir(join(root, 'frontend'));
  await mkdir(join(root, 'backend'));
  await writeFile(join(root, '.githooks', 'pre-commit'), '#!/bin/sh\nexit 0\n');
  await writeFile(join(root, '.claude', 'hooks', 'block-honjin-git.ps1'), 'hook');
  await writeFile(join(root, 'frontend', 'package.json'), JSON.stringify({ engines: { node: '22.23.2', npm: '10.9.8' } }));
  await writeFile(join(root, 'backend', 'gradlew'), 'gradle wrapper');
  return root;
}

async function run(root, ...args) {
  try { return await execFileAsync(process.execPath, [script, '--repo', root, '--json', ...args], { cwd: root }); }
  catch (error) { return { stdout: error.stdout ?? '', stderr: error.stderr ?? '', code: error.code }; }
}

test('既定実行は読み取り専用で、秘密の環境変数値を出力しない', async (t) => {
  const root = await fixture(t);
  const preCommit = join(root, '.git', 'hooks', 'pre-commit');
  const config = join(root, '.git', 'config');
  const before = [await readFile(preCommit).catch(() => null), await readFile(config)];
  const oldSecret = process.env.STRIPE_SECRET_KEY;
  process.env.STRIPE_SECRET_KEY = 'secret-value';
  const { stdout } = await run(root);
  if (oldSecret === undefined) delete process.env.STRIPE_SECRET_KEY;
  else process.env.STRIPE_SECRET_KEY = oldSecret;
  const after = [await readFile(preCommit).catch(() => null), await readFile(config)];
  assert.deepEqual(after, before);
  assert.doesNotMatch(stdout, /secret-value|token-value|password-value/i);
  const result = JSON.parse(stdout);
  assert.equal(result.installAttempted, false);
  assert.equal(result.claude.localHook, 'not-configured');
  assert.equal(typeof result.environment.CI, 'boolean');
  assert.equal(result.secretEnvironment.STRIPE_SECRET_KEY, true);
});

test('ツール実行失敗を欠損とtimeoutに分け、出力本文を公開しない', async () => {
  const { runTool } = await import('./harness-doctor.mjs');
  const missing = await runTool('missing', ['--version'], { spawn: () => { const error = new Error('missing'); error.code = 'ENOENT'; throw error; } });
  const timeout = await runTool('slow', ['--version'], { spawn: () => ({ error: { code: 'ETIMEDOUT' }, status: null, signal: 'SIGTERM', stdout: 'secret-value', stderr: 'token-value' }) });
  assert.equal(missing.status, 'missing');
  assert.equal(timeout.status, 'timeout');
  assert.doesNotMatch(JSON.stringify([missing, timeout]), /secret-value|token-value/);
});

test('Node/npm/Javaの実versionを期待値と比較し、hooksPath config originを保持する', async (t) => {
  const root = await fixture(t);
  const { diagnose } = await import('./harness-doctor.mjs');
  const spawn = (command) => {
    const output = command === process.execPath ? 'v22.23.2\n'
      : command === 'cmd.exe' ? '10.9.8\n'
        : command === 'java' ? 'openjdk version "17.0.10"\n'
          : '1.0.0\n';
    return { status: 0, stdout: command === 'java' ? '' : output, stderr: command === 'java' ? output : '' };
  };
  const result = await diagnose(root, { platform: 'win32', spawn });
  assert.equal(result.tools.node.status, 'available');
  assert.equal(result.tools.npm.status, 'available');
  assert.equal(result.tools.java.status, 'version-mismatch');
  assert.equal(result.hooks.hooksPathConfig.replaceAll('\\', '/').endsWith('/.git/hooks'), true);
  assert.match(result.hooks.hooksPathOrigin.replaceAll('\\', '/'), /\.git\/config/i);
});

test('既存の独自pre-commitを上書きせず、再導入は冪等', async (t) => {
  const root = await fixture(t);
  const target = join(root, '.git', 'hooks', 'pre-commit');
  await writeFile(target, '#!/bin/sh\necho custom\n');
  const conflict = JSON.parse((await run(root, '--install-hooks')).stdout);
  assert.equal(conflict.hookInstall.status, 'conflict');
  assert.equal(await readFile(target, 'utf8'), '#!/bin/sh\necho custom\n');
  await rm(target);
  const installed = JSON.parse((await run(root, '--install-hooks')).stdout);
  assert.equal(installed.hookInstall.status, 'installed');
  const content = await readFile(target, 'utf8');
  if (process.platform !== 'win32') assert.notEqual((await stat(target)).mode & 0o111, 0);
  const repeated = JSON.parse((await run(root, '--install-hooks')).stdout);
  assert.equal(repeated.hookInstall.status, 'already-installed');
  assert.equal(await readFile(target, 'utf8'), content);
});
