import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';

// 手順そのものを実行し、OSのプロセス列挙だけを有限の無害なfixtureへ置き換える。
const document = readFileSync(new URL('../docs/development/openapi_json_regeneration.md', import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const script = document.match(/```bash\n(MINE=\$\(git rev-parse --show-toplevel\)[\s\S]*?)\n```/)[1];
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : '/bin/bash';
const own = 'agent-2328';
const command = (args, tree = own) => `java -jar C:/fixture/worktrees/${tree}/backend/gradle/wrapper/gradle-wrapper.jar ${args}`;

function run(t, { cmd, winRows, winRc = 0, wslRows = '', wslRc = 0 } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'openapi-preflight-'));
  t.after(() => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    rmSync(root, { recursive: true, force: true });
  });
  const stubs = `
git() { printf '%s\\n' '/fixture/worktrees/${own}'; }
powershell.exe() { printf '%s\\n' "$FIXTURE_WINDOWS"; return "$FIXTURE_WINDOWS_RC"; }
wsl() { printf '%s\\n' "$FIXTURE_WSL"; return "$FIXTURE_WSL_RC"; }
`;
  // 末尾の判定をexit statusに依存させず、既存どおり出力から進行可否を検証する。
  // Windowsのargv quotingに文書のbackslashを変換させず、原文をstdinで渡す。
  const result = spawnSync(bash, ['--noprofile', '--norc', '-s'], {
      input: stubs + script,
      encoding: 'utf8', timeout: 10000,
      env: { ...process.env, BASH_ENV: '', ENV: '', TMPDIR: root, FIXTURE_WINDOWS: winRows ?? (cmd ? `123|${cmd}` : ''), FIXTURE_WINDOWS_RC: String(winRc), FIXTURE_WSL: wslRows, FIXTURE_WSL_RC: String(wslRc) },
      stdio: ['pipe', 'pipe', 'pipe'],
  });
  if (result.error) throw result.error;
  assert.equal(result.stderr, '', '分類器の実行エラーをUNKNOWNと誤認しない');
  return { status: result.status, stdout: result.stdout };
}

for (const [name, args, expected] of [
  ['通常のdry-run', 'generateOpenApiDocs --dry-run', 'DRY_RUN'],
  ['引用符で囲まれた単独token', 'generateOpenApiDocs "--dry-run"', 'DRY_RUN'],
  ['Windowsのbackslashを含む引数', String.raw`generateOpenApiDocs "-Pnote=C:\fixture\path" --dry-run`, 'DRY_RUN'],
  ['閉じ引用符の直前の偶数backslash', String.raw`generateOpenApiDocs "-Pnote=C:\fixture\\" --dry-run`, 'DRY_RUN'],
  ['escaped引用符内の偽token', String.raw`generateOpenApiDocs "-Pnote=say \"--dry-run\" words"`, 'GENERATE'],
  ['二重引用符内の偽token', 'generateOpenApiDocs "-Pnote=say ""--dry-run"" words"', 'GENERATE'],
  ['project修飾task', ':backend:generateOpenApiDocs --dry-run', 'DRY_RUN'],
  ['実生成', 'generateOpenApiDocs -PopenApiPort=8098', 'GENERATE'],
  ['値の一部はdry-runではない', 'generateOpenApiDocs -Pnote=--dry-run', 'GENERATE'],
  ['quoted値内の単語はdry-runではない', 'generateOpenApiDocs "-Pnote= --dry-run "', 'GENERATE'],
  ['別の引数値はdry-runではない', 'generateOpenApiDocs --dry-run=false', 'GENERATE'],
  ['別flagの値になり得るtokenは断定しない', 'generateOpenApiDocs --project-dir "--dry-run"', 'UNKNOWN'],
  ['不完全な引用符', 'generateOpenApiDocs "--dry-run', 'UNKNOWN'],
  ['task名が別の値に埋まる', '-Pnote=generateOpenApiDocs --dry-run', 'UNKNOWN'],
  ['forkの引数はGradleのdry-runではない', '-Dspring.profiles.active=openapi-gen --dry-run', 'GENERATE'],
]) {
  test(`${name}を${expected}として表示し進行可否を区別する`, t => {
    const result = run(t, { cmd: command(args) });
    assert.equal(result.status, 0);
    assert.match(result.stdout, new RegExp(`mode=${expected}`));
    assert.equal(result.stdout.includes('手順2 へ'), expected === 'DRY_RUN');
    assert.equal(result.stdout.includes('起動しない'), expected !== 'DRY_RUN');
    assert.doesNotMatch(result.stdout, /gradle-wrapper.jar|C:\/fixture|openApiPort|note=/);
  });
}

test('Gradle以外のJavaの引数を下見と誤認しない', t => {
  const result = run(t, { cmd: command('generateOpenApiDocs --dry-run').replace('gradle-wrapper.jar', 'unrelated.jar') });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode=UNKNOWN/);
  assert.match(result.stdout, /起動しない/);
  assert.doesNotMatch(result.stdout, /手順2 へ/);
});

test('所有不明はdry-runでも停止する', t => {
  const result = run(t, { cmd: 'java -jar C:/fixture/gradle-wrapper.jar generateOpenApiDocs --dry-run' });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode=DRY_RUN/);
  assert.match(result.stdout, /確認不能/);
  assert.match(result.stdout, /起動しない/);
  assert.doesNotMatch(result.stdout, /手順2 へ/);
});

test('別worktreeは区分を示して所有する出力先を妨げない', t => {
  const result = run(t, { cmd: command('generateOpenApiDocs --dry-run', 'other') });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode=DRY_RUN/);
  assert.match(result.stdout, /他 worktree/);
  assert.match(result.stdout, /手順2 へ/);
});

test('混在する自分の実生成があればdry-runがあっても停止する', t => {
  const result = run(t, { winRows: `123|${command('generateOpenApiDocs --dry-run')}\n124|${command('generateOpenApiDocs')}` });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode=DRY_RUN/);
  assert.match(result.stdout, /mode=GENERATE/);
  assert.match(result.stdout, /起動しない/);
  assert.doesNotMatch(result.stdout, /手順2 へ/);
});

test('WSLで区分を観測できない自分のprocessはUNKNOWNとして停止する', t => {
  const result = run(t, { wslRows: `456 /fixture/worktrees/${own}` });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /mode=UNKNOWN/);
  assert.match(result.stdout, /起動しない/);
  assert.doesNotMatch(result.stdout, /手順2 へ/);
});

test('WSLのcwd不明は停止し、別worktreeだけなら進める', t => {
  assert.match(run(t, { wslRows: '456' }).stdout, /起動しない/);
  assert.match(run(t, { wslRows: '456 /fixture/worktrees/other' }).stdout, /手順2 へ/);
});

for (const [name, fixture] of [['Windows', { winRc: 3 }], ['WSL', { wslRc: 127 }]]) {
  test(`${name}列挙失敗はexit2で停止する`, t => {
    const result = run(t, fixture);
    assert.equal(result.status, 2);
    assert.match(result.stdout, /確認不能/);
    assert.doesNotMatch(result.stdout, /手順2 へ/);
  });
}

test('対象なしなら次の手順へ進める', t => {
  const result = run(t);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /手順2 へ/);
  assert.doesNotMatch(result.stdout, /起動しない/);
});
