import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkTaskListStructure, splitTableRow } from './task-list-structure-check.mjs';

// CMP-261121-1114: merge queue（merge_group）経路の台帳構造検査器の単体テスト。
// 判定規則は backend/src/test/java/com/mannschaft/app/common/architecture/TaskListCmpIdDuplicateGuardTest.java
// （Java 番人）から移植した。下の共通 fixture は、Java 番人に同じ台帳を与えた場合の判定（expectOk）を期待値にしている。

const scriptPath = fileURLToPath(new URL('./task-list-structure-check.mjs', import.meta.url));
const repoTaskList = fileURLToPath(new URL('../docs/task-list.md', import.meta.url));
const header = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (id, state, rest = '— | 条件 | 証拠 | ledger') => '| ' + id + ' | 戦役 | ' + state + ' | ' + rest + ' |';
const doc = (...rows) => '# 台帳\n\n' + header + '\n' + rows.join('\n') + '\n';
const escapeRegExp = (s) => s.replace(/[.*+?^$(){}|[\]\\]/g, '\\$&');

/** 共通 fixture: [名前, 台帳本文, Java 番人と同じ期待判定, 失敗理由に含まれるべき語] */
const fixtures = [
  ['完了行のみの正常台帳', doc(row('CMP-001', '完了'), row('CMP-260101-0101', '完了（2026-01-01・PR #1）')), true],
  ['新旧ID形式の混在と優先度付き未完行', doc(row('CMP-028', 'P1 未着手'), row('CMP-260819-2131', 'P4 実装中'), row('CMP-260819-2132', '完了')), true],
  ['セル内の \\| は列区切りにしない', doc(row('CMP-260101-0101', '完了', '— | `a \\| b` を含む条件 | 証拠 \\| 補足 | ledger')), true],
  ['強調記法の状態セル（**P2 未着手** / **完了**）', doc(row('CMP-260101-0101', '**P2 未着手**'), row('CMP-260101-0102', '**完了**')), true],
  ['既存の完了系状態語', doc(...['取り下げ', '取下げ', '取下', '重複', '不要', '対応不要', '検証済み', '対応済み', '合格'].map((s, i) => row('CMP-260101-01' + String(i).padStart(2, '0'), s))), true],
  ['新形式IDの重複', doc(row('CMP-260101-0101', '完了'), row('CMP-260101-0101', 'P3 未着手')), false, 'CMP-260101-0101'],
  ['旧形式IDの重複', doc(row('CMP-028', '完了'), row('CMP-028', '完了')), false, 'CMP-028'],
  ['同日の別IDは重複にしない（日時形式を先に照合）', doc(row('CMP-260819-2131', '完了'), row('CMP-260819-2132', '完了')), true],
  ['列数8（コードスパン内の未エスケープの |）', doc(row('CMP-260101-0101', '完了', '— | a | b `x | y` | ledger')), false, '列数'],
  ['列数6', doc(row('CMP-260101-0101', '完了', '— | 条件 | ledger')), false, '列数'],
  ['未完行の優先度欠落', doc(row('CMP-260101-0101', '未着手')), false, '優先度'],
  ['実機検証待ちは完了系ではない', doc(row('CMP-260101-0101', '実機検証待ち')), false, '優先度'],
  ['P の後ろに空白が無い（P1未着手）', doc(row('CMP-260101-0101', 'P1未着手')), false, '優先度'],
  ['P6 は範囲外', doc(row('CMP-260101-0101', 'P6 未着手')), false, '優先度'],
  ['空の台帳', '', false, '空'],
  ['空白だけの台帳', ' \n\n', false, '空'],
  ['CMP 行が1件も無い台帳', '# 台帳\n\n' + header + '\n', false, '1件も'],
  ['先頭セルが壊れた CMP 行（緩い走査と件数不一致）', doc(row('CMP-260101-0101', '完了'), '| xCMP-260101-0102 | 戦役 | 完了 | — | 条件 | 証拠 | ledger |'), false, '件数'],
  ['先頭セル以外にだけ CMP-ID を持つ表の行（緩い走査と件数不一致）', doc(row('CMP-260101-0101', '完了'), '| 補足 | CMP-260101-0101 を参照 | 完了 | — | 条件 | 証拠 | ledger |'), false, '件数'],
  ['全角空白で始まる先頭セル（Java の \\s・trim は ASCII のみ。緩い走査と件数不一致）', doc(row('CMP-260101-0101', '完了'), '|　CMP-260101-0102 | 戦役 | 完了 | — | 条件 | 証拠 | ledger |'), false, '件数'],
  ['NEL（U+0085）の後ろの行も行頭として数え、重複を検出する（Java の (?m)^ と同じ）', doc(row('CMP-260101-0101', '完了') + '\n#\x85' + row('CMP-260101-0101', '完了')), false, 'CMP-260101-0101'],
  ['U+2028 の後ろの行も行頭として数え、重複を検出する', doc(row('CMP-260101-0101', '完了') + '\n#\u{2028}' + row('CMP-260101-0101', '完了')), false, 'CMP-260101-0101'],
  ['CR 単独の後ろの行も行頭として数え、重複を検出する', doc(row('CMP-260101-0101', '完了') + '\n#\r' + row('CMP-260101-0101', '完了')), false, 'CMP-260101-0101'],
  ['CRLF 改行の正常台帳', doc(row('CMP-260101-0101', '完了')).replace(/\n/g, '\r\n'), true],
];

for (const [name, content, expectOk, reason] of fixtures) {
  test('共通fixture: ' + name + ' → ' + (expectOk ? 'success' : 'fail'), () => {
    const result = checkTaskListStructure(content);
    assert.equal(result.ok, expectOk, JSON.stringify(result.errors));
    if (expectOk) {
      assert.deepEqual(result.errors, []);
    } else {
      assert.ok(result.errors.length > 0);
      if (reason) assert.match(result.errors.join('\n'), new RegExp(escapeRegExp(reason)));
    }
  });
}

test('ファイル不在（null）は例外ではなく明示 fail', () => {
  const result = checkTaskListStructure(null);
  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /存在しない/);
});

test('splitTableRow は \\| だけを区切りにせず、コードスパン内の生の | は区切りにする（Java 番人と同じ）', () => {
  assert.deepEqual(splitTableRow('| a \\| b | c |'), ['', ' a | b ', ' c ', '']);
  assert.deepEqual(splitTableRow('| `a | b` |'), ['', ' `a ', ' b` ', '']);
});

test('現行の docs/task-list.md は success（Java 番人が main で緑であることと一致）', () => {
  const result = checkTaskListStructure(readFileSync(repoTaskList, 'utf8'));
  assert.deepEqual(result.errors, []);
  assert.equal(result.ok, true);
  assert.ok(result.stats.rows > 0);
});

function runCli(args) {
  return spawnSync(process.execPath, [scriptPath, ...args], { encoding: 'utf8' });
}

async function withTaskList(content, fn) {
  const dir = await mkdtemp(join(tmpdir(), 'task-list-structure-'));
  try {
    const file = join(dir, 'task-list.md');
    await writeFile(file, content);
    return fn(file);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

test('CLI: 正常台帳は exit 0', async () => {
  await withTaskList(doc(row('CMP-260101-0101', '完了')), (file) => {
    const res = runCli([file]);
    assert.equal(res.status, 0, res.stdout + res.stderr);
  });
});

test('CLI: 違反台帳は理由を出力して exit 1（例外の stack で落ちない）', async () => {
  await withTaskList(doc(row('CMP-260101-0101', '未着手'), row('CMP-260101-0101', '完了')), (file) => {
    const res = runCli([file]);
    assert.equal(res.status, 1);
    const out = res.stdout + res.stderr;
    assert.match(out, /CMP-260101-0101/);
    assert.match(out, /優先度/);
    assert.match(out, /重複/);
    assert.doesNotMatch(out, /\n\s+at /);
  });
});

test('CLI: ファイル不在・引数なしは理由を出力して exit 1', () => {
  const missing = runCli([join(tmpdir(), 'no-such-dir-' + Date.now(), 'task-list.md')]);
  assert.equal(missing.status, 1);
  assert.match(missing.stdout + missing.stderr, /存在しない/);
  assert.doesNotMatch(missing.stdout + missing.stderr, /\n\s+at /);
  const noArg = runCli([]);
  assert.equal(noArg.status, 1);
  assert.doesNotMatch(noArg.stdout + noArg.stderr, /\n\s+at /);
});
