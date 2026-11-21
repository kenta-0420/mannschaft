#!/usr/bin/env node
// 台帳 docs/task-list.md の構造検査器（CMP-261121-1114）。
//
// Task-List PR Gate の merge queue（merge_group）経路で、合成コミットの台帳を検査するために使う。
// 判定規則は Java 番人 backend/src/test/java/com/mannschaft/app/common/architecture/TaskListCmpIdDuplicateGuardTest.java
// の3テスト（CMP-ID 重複＋走査経路の自己検証 / 列数7 / 未完行の優先度 P1〜P5）を移植したもの。
// 規則を変えるときは Java 番人と本スクリプト、scripts/task-list-structure-check.test.mjs の共通 fixture を同時に直すこと。
//
// 使い方: node scripts/task-list-structure-check.mjs <task-list.md のパス>
// 違反・ファイル不在・空・走査不能はすべて理由を出力して exit 1（例外の stack で落とさない）。
// 依存パッケージなし（GitHub ランナー既定の Node で動く）。

import { readFileSync, statSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

/** Java の \s（ASCII 空白のみ）。JS の \s は全角空白等も含み判定が Java 番人とずれるため明示する。 */
const JAVA_SPACE = '[ \\t\\n\\x0B\\f\\r]';
/** CMP-ID の形。日時形式を先に置く（CMP-\d+ を先にすると CMP-260819-2131 から CMP-260819 だけを拾う）。 */
export const CMP_ID_FORM = 'CMP-\\d{6}-\\d{4}|CMP-\\d+';
/**
 * Java の (?m)^ と同じ「行頭」。Java は LF・CRLF・CR・U+0085(NEL)・U+2028・U+2029 を行終端とし、
 * CR と LF の間は行頭にしない。JS の m フラグは NEL を行終端にしないため、後読みで明示する。
 */
const JAVA_LINE_START = '(?:^|(?<=[\\n\\x85\\u{2028}\\u{2029}])|(?<=\\r)(?!\\n))';
/** Java の .（行終端以外の1文字）。 */
const JAVA_DOT = '[^\\n\\r\\x85\\u{2028}\\u{2029}]';
/** 表の行頭に現れる CMP-ID（Java: CMP_ID_ROW）。 */
const CMP_ID_ROW = new RegExp(JAVA_LINE_START + '\\|' + JAVA_SPACE + '*(' + CMP_ID_FORM + ')' + JAVA_SPACE + '*\\|', 'gu');
/** 走査経路の自己検証用の緩い計数（Java: CMP_ID_ROW_LOOSE）。 */
const CMP_ID_ROW_LOOSE = new RegExp(JAVA_LINE_START + '\\|' + JAVA_DOT + '*?(' + CMP_ID_FORM + ')', 'gu');
const CMP_ID_PREFIX = new RegExp('^(' + CMP_ID_FORM + ')');
const CMP_ID_EXACT = new RegExp('^(' + CMP_ID_FORM + ')$');
/** 1行の列数（ID / 戦役 / 状態 / 依存 / 完了条件 / 証拠(PR/テスト) / 台帳）。 */
export const EXPECTED_COLUMN_COUNT = 7;
/** 優先度または完了系の語（前方一致。Java: STATUS_PRIORITY_OR_DONE）。 */
const STATUS_PRIORITY_OR_DONE = /^(P[1-5] |完了|取り下げ|取下げ|取下|重複|不要|対応不要|検証済み|対応済み|合格)/;

/** Java の String.trim()（U+0020 以下の文字だけを両端から除く）。JS の trim() は全角空白も除くため使わない。 */
function javaTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.slice(start, end);
}

/**
 * GFM の表の1行をセル分割する（Java: splitTableRow）。
 * \| だけを区切りにしない。コードスパン内の生の | も区切りとして数える（GFM の描画と同じ）。
 */
export function splitTableRow(line) {
  const result = [];
  let buf = '';
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '\\' && line[i + 1] === '|') {
      buf += '|';
      i++;
      continue;
    }
    if (c === '|') {
      result.push(buf);
      buf = '';
      continue;
    }
    buf += c;
  }
  result.push(buf);
  return result;
}

/**
 * 台帳本文を検査する。content が null/undefined ならファイル不在として扱う。
 * @returns {{ ok: boolean, errors: string[], stats: { rows: number, looseRows: number } }}
 */
export function checkTaskListStructure(content) {
  const errors = [];
  const stats = { rows: 0, looseRows: 0 };
  if (typeof content !== 'string') {
    errors.push('docs/task-list.md が存在しない（読めない）。台帳が無い状態を「問題なし」とは判定しない。');
    return { ok: false, errors, stats };
  }
  // 改行は正規化しない。重複・件数照合は Java の (?m)^ と同じ行終端（LF・CRLF・CR・NEL・U+2028・U+2029）で、
  // 列数・優先度は Java と同じく LF だけで行を分ける（CRLF の CR は末尾の空セルに入るだけで判定に影響しない）。
  const text = content;
  if (text.trim() === '') {
    errors.push('docs/task-list.md が空である。台帳が空の状態を「問題なし」とは判定しない。');
    return { ok: false, errors, stats };
  }

  // 1. CMP-ID の重複と、走査経路の自己検証（Java: cmpIdは重複しない）
  const occurrences = new Map();
  for (const match of text.matchAll(CMP_ID_ROW)) occurrences.set(match[1], (occurrences.get(match[1]) || 0) + 1);
  const strictCount = [...occurrences.values()].reduce((sum, n) => sum + n, 0);
  const looseCount = [...text.matchAll(CMP_ID_ROW_LOOSE)].length;
  stats.looseRows = looseCount;
  if (looseCount === 0) {
    errors.push('CMP-ID を含む表の行を1件も見つけられなかった。走査経路か台帳の表形式が壊れており、「重複なし」の判定自体が信用できない。');
  } else if (strictCount !== looseCount) {
    errors.push('CMP-ID を含む表の行は ' + looseCount + ' 件あるが、先頭セルの CMP-ID として厳密に抽出できたのは ' + strictCount
      + ' 件で件数が一致しない。先頭セルが壊れた行、または先頭セル以外にだけ CMP-ID を持つ表の行がある（取りこぼした行の重複を見逃すため fail とする）。');
  }
  for (const [id, count] of occurrences) {
    if (count > 1) errors.push('CMP-ID の重複: ' + id + ' が ' + count + ' 回出現する。後から main へ入った側が採番し直すこと。');
  }

  // 2. 列数（Java: cmpIdの行は列数が7である）と 3. 優先度（Java: cmp行の状態セルは優先度か完了系で始まる）
  let rows = 0;
  for (const line of text.split('\n')) {
    if (!line.startsWith('|')) continue;
    const cells = splitTableRow(line);
    if (cells.length < 2) continue;
    const firstCell = javaTrim(cells[1]);
    if (!CMP_ID_PREFIX.test(firstCell)) continue;
    rows++;
    const columns = cells.length - 2;
    if (columns !== EXPECTED_COLUMN_COUNT) {
      errors.push('列数: ' + firstCell + ' の行は ' + columns + ' 列（期待 ' + EXPECTED_COLUMN_COUNT + ' 列）。セル内の | は \\| にエスケープすること（コードスパン内でも）。');
    }
    if (cells.length < 4 || !CMP_ID_EXACT.test(firstCell)) continue;
    const status = javaTrim(cells[3].replace(/\*/g, ''));
    if (!STATUS_PRIORITY_OR_DONE.test(status)) {
      const head = status.length > 30 ? status.slice(0, 30) + '…' : status;
      errors.push('優先度: ' + firstCell + ' の状態セル "' + head + '" が P1〜P5（後ろに空白）または完了系の語で始まらない。未完の行は先頭に優先度を書くこと。');
    }
  }
  stats.rows = rows;
  if (rows === 0 && looseCount > 0) {
    errors.push('先頭セルに CMP-ID を持つ表の行を1件も見つけられなかった。走査経路（splitTableRow / CMP_ID_FORM）が壊れている可能性がある。');
  }
  return { ok: errors.length === 0, errors, stats };
}

/** CLI 本体。終了コードを返す（0: 問題なし / 1: 違反・読めない）。 */
export function main(argv, out = console.log, err = console.error) {
  const [filePath] = argv;
  if (!filePath) {
    err('使い方: node scripts/task-list-structure-check.mjs <docs/task-list.md のパス>');
    return 1;
  }
  let content = null;
  try {
    if (statSync(filePath, { throwIfNoEntry: false })?.isFile()) content = readFileSync(filePath, 'utf8');
  } catch (error) {
    err('docs/task-list.md を読めなかった（' + filePath + '）: ' + error.message);
    return 1;
  }
  const result = checkTaskListStructure(content);
  if (!result.ok) {
    err('台帳構造検査: NG（' + filePath + '）');
    for (const message of result.errors) err('  ✗ ' + message);
    return 1;
  }
  out('台帳構造検査: OK（' + filePath + '、CMP 行 ' + result.stats.rows + ' 件。重複・列数・優先度の違反なし）');
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
