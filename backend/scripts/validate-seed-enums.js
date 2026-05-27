'use strict';

/**
 * seed-e2e-data.js の INSERT 文に埋め込まれた enum 文字列値が
 * Java enum の有効値と一致するか静的に検証するスクリプト。
 *
 * 背景:
 *   Java の @Enumerated(EnumType.STRING) は enum 定数名と完全一致しない
 *   文字列を受け取ると HTTP 500 を引き起こす。
 *   このスクリプトを CI で実行することで、シードデータの不正値を早期に検出する。
 *
 * 実行: cd backend/scripts && node validate-seed-enums.js
 * CI:   node backend/scripts/validate-seed-enums.js
 */

const fs = require('fs');
const path = require('path');

// ============================================================
// 検証対象の定義
// ============================================================

/**
 * ENUM_CHECKS: 各カラムに対応する Java enum ファイルと抽出方法を定義する。
 *
 * extractFrom:
 *   'hardcoded' ... createSchedule 関数内の conn.execute() JS 配列に
 *                   ハードコードされた値を、列名で検索して検査する
 *   'callers'   ... createSchedule() 呼び出し元の第4引数（eventType）を検査する
 *
 * columnName:
 *   'hardcoded' の場合、SQL 列定義行から列の位置を特定するための列名。
 *   INSERT 列宣言にある名前と一致させること。
 */
const ENUM_CHECKS = [
  {
    column: 'event_type',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/EventType.java',
    extractFrom: 'callers',
  },
  {
    column: 'visibility',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/ScheduleVisibility.java',
    extractFrom: 'hardcoded',
    columnName: 'visibility',
  },
  {
    column: 'min_view_role',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/MinViewRole.java',
    extractFrom: 'hardcoded',
    columnName: 'min_view_role',
  },
  {
    column: 'min_response_role',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/MinResponseRole.java',
    extractFrom: 'hardcoded',
    columnName: 'min_response_role',
  },
  {
    column: 'status',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/ScheduleStatus.java',
    extractFrom: 'hardcoded',
    columnName: 'status',
  },
  {
    column: 'attendance_status',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/AttendanceGenerationStatus.java',
    extractFrom: 'hardcoded',
    columnName: 'attendance_status',
  },
  {
    column: 'comment_option',
    enumFile: '../src/main/java/com/mannschaft/app/schedule/CommentOption.java',
    extractFrom: 'hardcoded',
    columnName: 'comment_option',
  },
];

// ============================================================
// Java enum ファイルから有効な定数名を抽出する
// ============================================================

/**
 * Java enum ファイルを読み込み、定数名のセットを返す。
 * `    [A-Z][A-Z0-9_]*` で始まる行（末尾のカンマ・セミコロン有無を問わず）から抽出する。
 *
 * @param {string} absPath - Java enum ファイルの絶対パス
 * @returns {Set<string>|null} - 定数名のセット。ファイルが存在しない場合は null
 */
function extractEnumValues(absPath) {
  if (!fs.existsSync(absPath)) {
    return null;
  }
  const content = fs.readFileSync(absPath, 'utf8');
  const values = new Set();
  // 行頭スペース4つ＋大文字始まりの定数名。末尾がカンマ/セミコロン/空白/行末のいずれでもマッチ（最終定数の取りこぼしを防ぐ）
  const pattern = /^\s{4}([A-Z][A-Z0-9_]*)(?:[,;]|\s|$)/gm;
  let match;
  while ((match = pattern.exec(content)) !== null) {
    values.add(match[1]);
  }
  return values;
}

// ============================================================
// seed-e2e-data.js から VALUES 配列のテキストを抽出するユーティリティ
// ============================================================

/**
 * createSchedule 関数内の conn.execute() 呼び出しから
 * INSERT 列定義と JS VALUES 配列を抽出する。
 *
 * @param {string} seedContent - seed-e2e-data.js の全テキスト
 * @returns {{ columns: string[], jsArray: string[] }|null}
 *   columns: INSERT の列名配列（all_day など定数値の列を含む全列）
 *   jsArray: conn.execute() 第2引数の JS 配列要素（文字列 or 識別子）
 *   ※ SQL プレースホルダ '?' と JS 配列は 1:1 対応しない。
 *      all_day=0, is_exception=0 は SQL にハードコードされ JS 配列にはない。
 */
function extractCreateScheduleBody(seedContent) {
  // createSchedule 関数定義の開始位置を特定する
  const funcStart = seedContent.indexOf('async function createSchedule');
  if (funcStart === -1) return null;

  // 関数終端（`  }` の行）を探す
  const funcEnd = seedContent.indexOf('\n  }', funcStart);
  if (funcEnd === -1) return null;

  const funcBody = seedContent.slice(funcStart, funcEnd + 4);

  // --- INSERT 列定義を抽出する ---
  // `(team_id, organization_id, ...)` の部分を取得する
  const colListMatch = funcBody.match(/INSERT IGNORE INTO schedules\s*\(([^)]+)\)/s);
  if (!colListMatch) return null;

  const columns = colListMatch[1]
    .replace(/\r\n|\r|\n/g, ' ')   // 改行を空白に置換
    .split(',')
    .map(c => c.trim())
    .filter(Boolean);

  // --- JS 配列を抽出する ---
  // テンプレートリテラルの後ろにある `[...]` の第2引数を特定する。
  // バッククォート終端 `,` の次の `[` から始まる配列ブロックを探す。
  const backtickEnd = funcBody.lastIndexOf('`');
  if (backtickEnd === -1) return null;

  const afterBacktick = funcBody.slice(backtickEnd + 1);
  const arrayStart = afterBacktick.indexOf('[');
  if (arrayStart === -1) return null;

  const arrayEnd = afterBacktick.lastIndexOf(']');
  if (arrayEnd === -1 || arrayEnd <= arrayStart) return null;

  const arrayContent = afterBacktick.slice(arrayStart + 1, arrayEnd);
  const jsArray = tokenizeValueArray(arrayContent);

  return { columns, jsArray };
}

/**
 * VALUES 配列の文字列ブロックをトークン列に分解する。
 * シングルクォート文字列・ダブルクォート文字列・数値・識別子を認識する。
 *
 * @param {string} block - 配列の中身（角括弧除く）
 * @returns {string[]} - 各要素の文字列表現
 */
function tokenizeValueArray(block) {
  const tokens = [];
  let i = 0;
  const s = block;

  while (i < s.length) {
    const ch = s[i];

    // 空白・改行・改行コード・カンマをスキップ
    if (/[\s,]/.test(ch)) {
      i++;
      continue;
    }

    // シングルクォート文字列
    if (ch === "'") {
      let end = i + 1;
      while (end < s.length && s[end] !== "'") {
        if (s[end] === '\\') end++;
        end++;
      }
      tokens.push(s.slice(i, end + 1));
      i = end + 1;
      continue;
    }

    // ダブルクォート文字列
    if (ch === '"') {
      let end = i + 1;
      while (end < s.length && s[end] !== '"') {
        if (s[end] === '\\') end++;
        end++;
      }
      tokens.push(s.slice(i, end + 1));
      i = end + 1;
      continue;
    }

    // 数値 or 識別子（カンマ・空白まで）
    let end = i;
    while (end < s.length && !/[\s,]/.test(s[end])) {
      end++;
    }
    tokens.push(s.slice(i, end));
    i = end;
  }

  return tokens;
}

/**
 * SQL の INSERT 列宣言と JS 配列を対応させ、指定列名の JS 値を返す。
 *
 * INSERT 文の VALUES プレースホルダ `?` と ハードコード値 `0` の位置関係を
 * 考慮して、JS 配列の対応インデックスを算出する。
 *
 * SQL プレースホルダ行例:
 *   VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,0,?,?,?)
 *
 * JS 配列にはハードコード値（`0` など）は含まれない。
 * '?' の出現順序が JS 配列のインデックスと対応する。
 *
 * @param {string} seedContent - seed-e2e-data.js の全テキスト
 * @param {string} targetColumnName - 対象の列名
 * @returns {string|null} - 対応する JS 値（文字列リテラルの場合はクォート除去済み）
 */
function getHardcodedValueForColumn(seedContent, targetColumnName) {
  const funcStart = seedContent.indexOf('async function createSchedule');
  if (funcStart === -1) return null;
  const funcEnd = seedContent.indexOf('\n  }', funcStart);
  const funcBody = seedContent.slice(funcStart, funcEnd + 4);

  // INSERT 列定義を取得する
  const colListMatch = funcBody.match(/INSERT IGNORE INTO schedules\s*\(([^)]+)\)/s);
  if (!colListMatch) return null;

  const columns = colListMatch[1]
    .replace(/\r\n|\r|\n/g, ' ')
    .split(',')
    .map(c => c.trim())
    .filter(Boolean);

  const targetIdx = columns.indexOf(targetColumnName);
  if (targetIdx === -1) return null;

  // VALUES プレースホルダ行を取得する（テンプレートリテラル内）
  const valuesLineMatch = funcBody.match(/VALUES\s*\(([^)]+)\)/s);
  if (!valuesLineMatch) return null;

  const valuesLine = valuesLineMatch[1]
    .replace(/\r\n|\r|\n/g, ' ')
    .split(',')
    .map(v => v.trim())
    .filter(Boolean);

  // targetIdx の位置にあるプレースホルダが '?' かどうか確認する
  if (valuesLine[targetIdx] !== '?') {
    // ハードコード値（数値等）なので検証対象外
    return null;
  }

  // '?' の何番目かを数える（0始まり）= JS 配列のインデックス
  let questionCount = -1;
  for (let i = 0; i <= targetIdx; i++) {
    if (valuesLine[i] === '?') questionCount++;
  }

  // JS 配列から対応インデックスの値を取得する
  const jsArrayExtract = extractCreateScheduleJsArray(funcBody);
  if (!jsArrayExtract || questionCount >= jsArrayExtract.length) return null;

  const token = jsArrayExtract[questionCount];
  // シングルクォート文字列の場合はクォートを除去して返す
  if (token && token.startsWith("'") && token.endsWith("'")) {
    return token.slice(1, -1);
  }
  // 識別子（変数参照）の場合は null を返す（静的検証対象外）
  return null;
}

/**
 * createSchedule 関数本体から conn.execute() の第2引数 JS 配列を抽出する。
 *
 * @param {string} funcBody - createSchedule 関数のソーステキスト
 * @returns {string[]|null} - トークン列
 */
function extractCreateScheduleJsArray(funcBody) {
  const backtickEnd = funcBody.lastIndexOf('`');
  if (backtickEnd === -1) return null;
  const afterBacktick = funcBody.slice(backtickEnd + 1);
  const arrayStart = afterBacktick.indexOf('[');
  if (arrayStart === -1) return null;
  const arrayEnd = afterBacktick.lastIndexOf(']');
  if (arrayEnd === -1 || arrayEnd <= arrayStart) return null;
  return tokenizeValueArray(afterBacktick.slice(arrayStart + 1, arrayEnd));
}

/**
 * createSchedule() 呼び出しの第4引数（eventType）をすべて抽出する。
 *
 * @param {string} seedContent - seed-e2e-data.js の全テキスト
 * @returns {string[]} - 抽出された eventType 文字列値の配列
 */
function extractCallerEventTypes(seedContent) {
  // await createSchedule(arg0, arg1, arg2, 'EVENTTYPE', ...) の形式を想定
  // ただし引数が複数行にまたがる場合があるため、関数全体から `await createSchedule(` ブロックを抽出する
  const results = [];
  let searchFrom = 0;

  while (true) {
    const callStart = seedContent.indexOf('await createSchedule(', searchFrom);
    if (callStart === -1) break;

    // 対応する閉じ括弧を探す
    let depth = 0;
    let callEnd = callStart + 'await createSchedule('.length - 1;
    for (let i = callEnd; i < seedContent.length; i++) {
      if (seedContent[i] === '(') depth++;
      else if (seedContent[i] === ')') {
        depth--;
        if (depth === 0) {
          callEnd = i;
          break;
        }
      }
    }

    const argsText = seedContent.slice(
      callStart + 'await createSchedule('.length,
      callEnd
    );
    const args = tokenizeValueArray(argsText);

    // 第4引数（index 3）を取得
    if (args.length > 3) {
      const val = args[3];
      if (val && val.startsWith("'") && val.endsWith("'")) {
        results.push(val.slice(1, -1));
      }
    }

    searchFrom = callEnd + 1;
  }

  return results;
}

// ============================================================
// メイン検証ロジック
// ============================================================

const seedFilePath = path.resolve(__dirname, 'seed-e2e-data.js');

if (!fs.existsSync(seedFilePath)) {
  console.error(`❌ ERROR: seed-e2e-data.js が見つかりません: ${seedFilePath}`);
  process.exit(1);
}

const seedContent = fs.readFileSync(seedFilePath, 'utf8');

let hasError = false;

console.log('🔍 seed-e2e-data.js の enum 値を検証中...\n');

for (const check of ENUM_CHECKS) {
  const enumAbsPath = path.resolve(__dirname, check.enumFile);
  const validValues = extractEnumValues(enumAbsPath);

  if (validValues === null) {
    console.warn(`⚠️  WARNING: enum ファイルが見つかりません（スキップ）: ${check.enumFile}`);
    continue;
  }

  console.log(`[${check.column}] 有効値 = [${[...validValues].join(', ')}]`);

  // 使用されている値を抽出する
  let usedValues;
  if (check.extractFrom === 'callers') {
    usedValues = extractCallerEventTypes(seedContent);
  } else {
    // hardcoded: ハードコード値を列名で特定する
    const val = getHardcodedValueForColumn(seedContent, check.columnName);
    usedValues = val !== null ? [val] : [];
  }

  if (usedValues.length === 0) {
    console.log(`  （対象値なし – スキップ）\n`);
    continue;
  }

  // 重複を除いてユニーク値のみ検証する
  const uniqueUsed = [...new Set(usedValues)];

  let columnHasError = false;
  for (const val of uniqueUsed) {
    if (!validValues.has(val)) {
      console.error(
        `  ❌ INVALID: ${check.column}='${val}' は有効な enum 値ではありません。` +
        `有効値: [${[...validValues].join(', ')}]`
      );
      hasError = true;
      columnHasError = true;
    } else {
      console.log(`  ✓  '${val}' は有効`);
    }
  }
  if (!columnHasError) {
    console.log('');
  }
}

if (hasError) {
  console.error('\n❌ seed-e2e-data.js に不正な enum 値が含まれています。修正してください。');
  process.exit(1);
} else {
  console.log('\n✅ All enum values in seed-e2e-data.js are valid');
  process.exit(0);
}
