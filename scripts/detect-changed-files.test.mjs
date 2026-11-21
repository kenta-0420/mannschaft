import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, unlinkSync, renameSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// 必須チェック（Compile & Test / OpenAPI Drift Check / ER Diagram Drift Check）の
// 変更判定に使う共通スクリプト .github/scripts/detect-changed-files.sh の単体テスト。
// 一時 git リポジトリを作り、pull_request / merge_group / workflow_dispatch / 空SHA /
// diff 失敗 / 改名・削除 の各分岐を実際の git で検証する（CMP-261121-1114）。

const repository = fileURLToPath(new URL('../', import.meta.url));
const script = join(repository, '.github', 'scripts', 'detect-changed-files.sh');

// 終了コードの契約（スクリプト冒頭のコメントと一致させる）
const EXIT_OK = 0;
const EXIT_EMPTY_SHA = 2;
const EXIT_DIFF_FAILED = 3;
const EXIT_FULL_RUN_EVENT = 10;

function git(cwd, ...args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} が失敗: ${result.stderr}`);
  }
  return result.stdout.trim();
}

function write(root, path, content) {
  const full = join(root, path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, content);
}

function commit(root, message) {
  git(root, 'add', '-A');
  git(root, 'commit', '-q', '-m', message);
  return git(root, 'rev-parse', 'HEAD');
}

function detect(cwd, ...args) {
  const result = spawnSync('bash', [script, ...args], { cwd, encoding: 'utf8' });
  return {
    status: result.status,
    files: result.stdout.split(/\r?\n/).filter((line) => line.length > 0).sort(),
    stderr: result.stderr,
  };
}

function makeRepo(t) {
  const root = mkdtempSync(join(tmpdir(), 'mannschaft-detect-changed-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  git(root, 'init', '-q', '-b', 'main');
  git(root, 'config', 'user.email', 'test@example.invalid');
  git(root, 'config', 'user.name', 'test');
  git(root, 'config', 'core.autocrlf', 'false');
  write(root, 'README.md', 'root\n');
  write(root, 'backend/src/main/java/App.java', 'class App {}\n');
  write(root, 'backend/src/main/resources/db/migration/V1__init.sql', 'select 1;\n');
  const initial = commit(root, 'initial');
  return { root, initial };
}

test('pull_request: base...head の3ドット差分で PR が加えた変更だけを返す', (t) => {
  const { root, initial } = makeRepo(t);
  git(root, 'checkout', '-q', '-b', 'feature');
  write(root, 'docs/guide.md', 'doc\n');
  const head = commit(root, 'docs only');
  // base 側（main）が分岐後に backend を進めても PR の差分には含めない
  git(root, 'checkout', '-q', 'main');
  write(root, 'backend/src/main/java/Other.java', 'class Other {}\n');
  const base = commit(root, 'main advanced');
  assert.notEqual(base, initial);

  const result = detect(root, 'pull_request', base, head);
  assert.equal(result.status, EXIT_OK, result.stderr);
  assert.deepEqual(result.files, ['docs/guide.md']);
});

test('merge_group: base_sha（先行エントリの合成コミット）...head_sha で、このエントリ自身の差分だけを返す', (t) => {
  const { root } = makeRepo(t);
  // queue で先行するエントリの merge group（合成コミット）。base_sha はここを指す。
  // 先行エントリは自身の merge group で検証済みで、落ちればキューが後続を再構築する。
  git(root, 'checkout', '-q', '-b', 'gh-readonly-queue/main/pr-1');
  write(root, 'backend/src/main/resources/db/migration/V2__add.sql', 'select 2;\n');
  const precedingComposite = commit(root, 'queue 先行エントリの合成コミット');
  // このエントリの merge group は先行エントリの合成コミットの上に自エントリを積む
  git(root, 'checkout', '-q', '-b', 'gh-readonly-queue/main/pr-2');
  write(root, 'docs/guide.md', 'doc\n');
  const head = commit(root, '自エントリ');

  const result = detect(root, 'merge_group', precedingComposite, head);
  assert.equal(result.status, EXIT_OK, result.stderr);
  assert.deepEqual(result.files, ['docs/guide.md']);
});

test('merge_group: 関連変更が無ければ空の一覧で正常終了する', (t) => {
  const { root, initial } = makeRepo(t);
  git(root, 'checkout', '-q', '-b', 'gh-readonly-queue/main/pr-3');
  git(root, 'commit', '-q', '--allow-empty', '-m', 'empty');
  const head = git(root, 'rev-parse', 'HEAD');
  const result = detect(root, 'merge_group', initial, head);
  assert.equal(result.status, EXIT_OK, result.stderr);
  assert.deepEqual(result.files, []);
});

for (const event of ['workflow_dispatch', 'push', 'schedule']) {
  test(`${event}: 差分判定の対象外イベントとしてフル実行を指示する（非0終了）`, (t) => {
    const { root, initial } = makeRepo(t);
    const result = detect(root, event, initial, initial);
    assert.equal(result.status, EXIT_FULL_RUN_EVENT, result.stderr);
    assert.deepEqual(result.files, []);
  });
}

for (const event of ['pull_request', 'merge_group']) {
  test(`${event}: base SHA が空なら判定不能として非0終了する（黙って空一覧を返さない）`, (t) => {
    const { root, initial } = makeRepo(t);
    const result = detect(root, event, '', initial);
    assert.equal(result.status, EXIT_EMPTY_SHA, result.stderr);
    assert.deepEqual(result.files, []);
    assert.match(result.stderr, /判定不能/);
  });

  test(`${event}: head SHA が空なら判定不能として非0終了する`, (t) => {
    const { root, initial } = makeRepo(t);
    const result = detect(root, event, initial, '');
    assert.equal(result.status, EXIT_EMPTY_SHA, result.stderr);
    assert.deepEqual(result.files, []);
  });

  test(`${event}: 引数が欠けていても判定不能として非0終了する`, (t) => {
    const { root } = makeRepo(t);
    const result = detect(root, event);
    assert.equal(result.status, EXIT_EMPTY_SHA, result.stderr);
  });

  test(`${event}: git diff が失敗（存在しない SHA）したら判定不能として非0終了する`, (t) => {
    const { root, initial } = makeRepo(t);
    const missing = '0123456789abcdef0123456789abcdef01234567';
    const result = detect(root, event, initial, missing);
    assert.equal(result.status, EXIT_DIFF_FAILED, result.stderr);
    assert.deepEqual(result.files, []);
    assert.match(result.stderr, /判定不能/);
  });
}

test('イベント名が空なら判定不能として非0終了する', (t) => {
  const { root, initial } = makeRepo(t);
  const result = detect(root, '', initial, initial);
  assert.equal(result.status, EXIT_EMPTY_SHA, result.stderr);
});

test('改名: 旧パスと新パスの両方を返す（--no-renames）', (t) => {
  const { root, initial } = makeRepo(t);
  git(root, 'checkout', '-q', '-b', 'feature');
  mkdirSync(join(root, 'docs'), { recursive: true });
  renameSync(
    join(root, 'backend/src/main/resources/db/migration/V1__init.sql'),
    join(root, 'docs/V1__init.sql'),
  );
  const head = commit(root, 'move migration out of backend');
  const result = detect(root, 'pull_request', initial, head);
  assert.equal(result.status, EXIT_OK, result.stderr);
  assert.deepEqual(result.files, ['backend/src/main/resources/db/migration/V1__init.sql', 'docs/V1__init.sql']);
});

test('削除: 削除したファイルの旧パスを返す', (t) => {
  const { root, initial } = makeRepo(t);
  git(root, 'checkout', '-q', '-b', 'gh-readonly-queue/main/pr-4');
  unlinkSync(join(root, 'backend/src/main/java/App.java'));
  const head = commit(root, 'delete');
  const result = detect(root, 'merge_group', initial, head);
  assert.equal(result.status, EXIT_OK, result.stderr);
  assert.deepEqual(result.files, ['backend/src/main/java/App.java']);
});
