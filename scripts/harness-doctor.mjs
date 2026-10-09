#!/usr/bin/env node
/** 開発環境の依存ツールと本陣保護フックを読み取り専用で診断する。 */
import { spawnSync } from 'node:child_process';
import { access, chmod, copyFile, mkdir, readFile, stat } from 'node:fs/promises';
import { constants } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_TIMEOUT_MS = 5000;
const MAX_OUTPUT_BYTES = 4096;
const SECRET_ENV_VARS = ['JWT_SECRET', 'STRIPE_SECRET_KEY', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'SPRING_DATASOURCE_PASSWORD', 'MAIL_PASSWORD'];

export function parseArgs(argv) {
  const options = { repo: process.cwd(), json: false, installHooks: false };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--repo') {
      if (!argv[i + 1]) throw new Error('--repo にはリポジトリパスが必要です');
      options.repo = resolve(argv[++i]);
    } else if (argv[i] === '--json') options.json = true;
    else if (argv[i] === '--install-hooks') options.installHooks = true;
    else throw new Error(`未対応の引数: ${argv[i]}`);
  }
  return options;
}

/** 子プロセスの本文は結果に含めない。shell:false・固定引数・時間と出力量の上限付き。 */
export async function runTool(command, args, { cwd, timeout = DEFAULT_TIMEOUT_MS, spawn = spawnSync } = {}) {
  try {
    const result = spawn(command, args, {
      cwd, shell: false, windowsHide: true, timeout, maxBuffer: MAX_OUTPUT_BYTES,
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (result.error?.code === 'ENOENT') return { status: 'missing' };
    if (result.error?.code === 'ETIMEDOUT' || result.signal === 'SIGTERM' || result.signal === 'SIGKILL') return { status: 'timeout' };
    if (result.error) return { status: 'failed' };
    const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
    const version = output.match(/(?:^|[\s"'v])(\d+\.\d+(?:\.\d+)?)(?=$|[\s"'])/)?.[1];
    return { status: result.status === 0 ? 'available' : 'failed', ...(version ? { version } : {}) };
  } catch (error) {
    if (error.code === 'ENOENT') return { status: 'missing' };
    if (error.code === 'ETIMEDOUT') return { status: 'timeout' };
    return { status: 'failed' };
  }
}

async function git(repo, args) {
  const result = spawnSync('git', args, { cwd: repo, shell: false, windowsHide: true, encoding: 'utf8', timeout: DEFAULT_TIMEOUT_MS, maxBuffer: MAX_OUTPUT_BYTES });
  if (result.error || result.status !== 0) throw new Error('Git設定を確認できませんでした');
  return (result.stdout ?? '').trim();
}

async function exists(path) {
  try { await access(path); return true; } catch { return false; }
}

async function inspectHooks(repo, install) {
  const commonDir = resolve(repo, await git(repo, ['rev-parse', '--path-format=absolute', '--git-common-dir']));
  const gitDir = resolve(repo, await git(repo, ['rev-parse', '--absolute-git-dir']));
  const hooksDir = resolve(repo, await git(repo, ['rev-parse', '--path-format=absolute', '--git-path', 'hooks']));
  let hooksPathConfig = 'unset';
  let hooksPathOrigin = 'default';
  const config = spawnSync('git', ['config', '--show-origin', '--get', 'core.hooksPath'], { cwd: repo, shell: false, windowsHide: true, encoding: 'utf8', timeout: DEFAULT_TIMEOUT_MS, maxBuffer: MAX_OUTPUT_BYTES });
  if (!config.error && config.status === 0) {
    const selected = (config.stdout ?? '').trim().split(/\t/);
    hooksPathOrigin = selected[0]?.replace(/^file:/, '') ?? 'configured';
    hooksPathConfig = selected.slice(1).join('\t') || 'configured';
  }
  const source = join(repo, '.githooks', 'pre-commit');
  const target = join(hooksDir, 'pre-commit');
  const sourceExists = await exists(source);
  const localSettingsPath = join(repo, '.claude', 'settings.local.json');
  const localSettingsExists = await exists(localSettingsPath);
  let claudeHook = 'not-configured';
  if (localSettingsExists) {
    try {
      const settings = JSON.parse(await readFile(localSettingsPath, 'utf8'));
      const hooks = settings.hooks?.PreToolUse ?? [];
      const hasCommand = (entry) => Array.isArray(entry?.hooks)
        && entry.hooks.some((hook) => typeof hook.command === 'string' && hook.command.includes('block-honjin-git.ps1'));
      const bash = hooks.find((entry) => entry.matcher === 'Bash');
      const powershell = hooks.find((entry) => entry.matcher === 'PowerShell');
      const bashConfigured = hasCommand(bash) && bash.hooks.some((hook) => hook.if === 'Bash(git *)');
      const powershellConfigured = hasCommand(powershell) && powershell.hooks.every((hook) => !Object.hasOwn(hook, 'if'));
      claudeHook = bashConfigured && powershellConfigured ? 'configured' : 'incomplete';
    } catch {
      claudeHook = 'invalid-settings';
    }
  }

  let gitHook = sourceExists ? 'missing' : 'source-missing';
  if (sourceExists && await exists(target)) {
    const [expected, actual] = await Promise.all([readFile(source), readFile(target)]);
    if (expected.equals(actual)) {
      const targetStat = await stat(target);
      gitHook = process.platform !== 'win32' && (targetStat.mode & 0o111) === 0 ? 'not-executable' : 'installed';
    } else gitHook = 'custom-hook-conflict';
  }

  let installResult = null;
  if (install) {
    if (gitHook === 'not-executable') {
      try {
        await chmod(target, (await stat(target)).mode | 0o111);
        gitHook = 'installed';
        installResult = { status: 'already-installed', message: '正本と一致するhookへ実行bitを付与しました' };
      } catch {
        installResult = { status: 'permission-required', message: 'Git hookへの実行bit設定に権限が必要です' };
      }
    }
    if (installResult) {
      // 同じhook内容の実行bit補修を済ませた。
    } else
    if (!sourceExists) installResult = { status: 'source-missing', message: '.githooks/pre-commit が見つかりません' };
    else if (gitHook === 'installed') installResult = { status: 'already-installed', message: 'Git hook は正本と一致しています' };
    else if (gitHook === 'custom-hook-conflict') installResult = { status: 'conflict', message: '既存のpre-commitを上書きしません。内容を確認して手動で統合してください' };
    else if (resolve(gitDir) !== resolve(commonDir)) {
      installResult = { status: 'outside-worktree', message: 'hookはgit共通ディレクトリにあります。書込権限のある本陣作業環境で実行してください' };
    } else {
      try {
        if (!(await exists(hooksDir))) {
          const insideCommon = resolve(hooksDir).startsWith(`${resolve(commonDir)}${process.platform === 'win32' ? '\\' : '/'}`);
          if (hooksPathConfig !== 'unset' && !insideCommon) {
            installResult = { status: 'custom-hooks-path', message: '設定済みの外部hooksPathを自動作成しません' };
            return { git: { commonDir, worktreeGitDir: gitDir, hooksPath: hooksDir, hooksPathConfig, hooksPathOrigin, source: sourceExists ? 'available' : 'missing', preCommit: gitHook }, claude: { settings: localSettingsExists ? 'available' : 'missing', localHook: claudeHook, guidance: 'docs/development/honjin_protection_setup.md を参照。Claude設定は自動変更しません' }, installResult };
          }
          await mkdir(hooksDir, { recursive: true });
        }
        await access(hooksDir, constants.W_OK);
        if (!(await stat(hooksDir)).isDirectory()) throw new Error('hooks path is not a directory');
        await copyFile(source, target, constants.COPYFILE_EXCL);
        if (process.platform !== 'win32') await chmod(target, (await stat(source)).mode | 0o111);
        installResult = { status: 'installed', message: 'Git pre-commitを導入しました' };
        gitHook = 'installed';
      } catch (error) {
        if (error.code === 'EEXIST') installResult = { status: 'conflict', message: '既存のpre-commitを上書きしませんでした' };
        else installResult = { status: 'permission-required', message: 'Git hooksディレクトリへの書込権限が必要です。権限のある環境で再実行してください' };
      }
    }
  }

  return {
    git: { commonDir, worktreeGitDir: gitDir, hooksPath: hooksDir, hooksPathConfig, hooksPathOrigin, source: sourceExists ? 'available' : 'missing', preCommit: gitHook },
    claude: { settings: localSettingsExists ? 'available' : 'missing', localHook: claudeHook, guidance: 'docs/development/honjin_protection_setup.md を参照。Claude設定は自動変更しません' },
    installResult,
  };
}

export async function diagnose(repo, { installHooks = false, platform = process.platform, spawn } = {}) {
  const requiredFiles = [
    '.githooks/pre-commit', '.claude/hooks/block-honjin-git.ps1', 'frontend/package.json', 'backend/gradlew',
  ];
  const files = {};
  for (const file of requiredFiles) files[file] = await exists(join(repo, file)) ? 'available' : 'missing';
  const toolCommands = [
    ['node', process.execPath, ['--version']],
    ['npm', platform === 'win32' ? 'cmd.exe' : 'npm', platform === 'win32' ? ['/d', '/s', '/c', 'npm --version'] : ['--version']],
    ['java', 'java', ['-version']],
    ['git', 'git', ['--version']],
    ['gh', 'gh', ['--version']],
    ['docker', 'docker', ['--version']],
  ];
  const tools = {};
  for (const [name, command, args] of toolCommands) tools[name] = await runTool(command, args, { cwd: repo, spawn });
  let expected = {};
  try {
    const packageJson = JSON.parse(await readFile(join(repo, 'frontend', 'package.json'), 'utf8'));
    expected = { node: packageJson.engines?.node, npm: packageJson.engines?.npm, javaMajor: 21 };
  } catch {
    expected = { configuration: 'missing-or-invalid' };
  }
  for (const name of ['node', 'npm']) {
    const version = tools[name].version;
    const wanted = expected[name];
    if (tools[name].status === 'available' && wanted && version && version !== wanted) tools[name].status = 'version-mismatch';
  }
  const javaVersion = tools.java.version;
  if (tools.java.status === 'available' && javaVersion && Number(javaVersion.split('.')[0]) !== expected.javaMajor) tools.java.status = 'version-mismatch';
  const hooks = await inspectHooks(repo, installHooks);
  const environment = {};
  for (const name of ['CI', 'JAVA_HOME', 'DOCKER_HOST', 'CLAUDE_PROJECT_DIR']) environment[name] = Boolean(process.env[name]);
  const secretEnvironment = Object.fromEntries(SECRET_ENV_VARS.map((name) => [name, Boolean(process.env[name])]));
  return { repo, tools, expectedVersions: expected, requiredFiles: files, hooks: hooks.git, claude: hooks.claude, hookInstall: hooks.installResult, installAttempted: installHooks, environment, secretEnvironment };
}

function render(result) {
  const lines = ['開発環境診断'];
  for (const [name, value] of Object.entries(result.tools)) {
    const expected = name === 'java' ? `${result.expectedVersions.javaMajor}.x` : result.expectedVersions[name];
    lines.push(`tool ${name}: ${value.status}${value.version ? ` (actual ${value.version}${expected ? ` / expected ${expected}` : ''})` : ''}`);
  }
  for (const [name, value] of Object.entries(result.requiredFiles)) lines.push(`file ${name}: ${value}`);
  lines.push(`git hook: ${result.hooks.preCommit} (${result.hooks.hooksPathOrigin})`, `Claude local hook: ${result.claude.localHook}`);
  for (const [name, present] of Object.entries(result.environment)) lines.push(`env ${name}: ${present ? 'set' : 'unset'}`);
  for (const [name, present] of Object.entries(result.secretEnvironment)) lines.push(`env ${name}: ${present ? 'set' : 'unset'}`);
  if (result.hookInstall) lines.push(`hook install: ${result.hookInstall.status} — ${result.hookInstall.message}`);
  lines.push(result.claude.guidance);
  return lines.join('\n');
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const result = await diagnose(options.repo, { installHooks: options.installHooks });
  console.log(options.json ? JSON.stringify(result, null, 2) : render(result));
  const failedTools = Object.values(result.tools).some((tool) => tool.status !== 'available');
  const failedFiles = Object.values(result.requiredFiles).some((status) => status !== 'available');
  const failedHook = result.hooks.preCommit !== 'installed';
  const failedInstall = result.hookInstall && !['installed', 'already-installed'].includes(result.hookInstall.status);
  if (failedTools || failedFiles || failedHook || failedInstall || result.claude.localHook !== 'configured') process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
