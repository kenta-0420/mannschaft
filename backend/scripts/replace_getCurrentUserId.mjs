/**
 * 全コントローラーの getCurrentUserId() を SecurityUtils.getCurrentUserId() に置換するスクリプト。
 *
 * 処理内容:
 * 1. TODO コメント + getCurrentUserId() メソッド定義を削除
 * 2. getCurrentUserId() の呼び出しを SecurityUtils.getCurrentUserId() に置換
 * 3. import com.mannschaft.app.common.SecurityUtils を追加
 */

import { readFileSync, writeFileSync } from 'fs';
import { glob } from 'fs/promises';
import { resolve, relative, basename } from 'path';

const SRC_ROOT = resolve(import.meta.dirname, '..', 'src', 'main', 'java');
const IMPORT_LINE = 'import com.mannschaft.app.common.SecurityUtils;';
const EXCLUDE = new Set(['SecurityUtils.java']);

// getCurrentUserId メソッド定義パターン（TODOコメント + 本体）
const METHOD_RE = /\n(    \/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*\n)?    private Long getCurrentUserId\(\) \{\n        return 1L;\n    \}\n/g;

let modifiedCount = 0;
const modifiedFiles = [];

const pattern = resolve(SRC_ROOT, 'com/mannschaft/app/**/*.java');

for await (const filepath of glob(pattern)) {
  if (filepath.includes('.claude') || EXCLUDE.has(basename(filepath))) continue;

  let content = readFileSync(filepath, 'utf-8');
  if (!content.includes('getCurrentUserId')) continue;

  const original = content;

  // 1. メソッド定義を削除
  content = content.replace(METHOD_RE, '\n');

  // 2. 呼び出しを置換
  content = content.replaceAll('getCurrentUserId()', 'SecurityUtils.getCurrentUserId()');

  // 3. import 追加
  if (!content.includes(IMPORT_LINE)) {
    const lastImportIdx = content.lastIndexOf('\nimport ');
    if (lastImportIdx !== -1) {
      const lineEnd = content.indexOf('\n', lastImportIdx + 1);
      content = content.slice(0, lineEnd + 1) + IMPORT_LINE + '\n' + content.slice(lineEnd + 1);
    }
  }

  if (content !== original) {
    writeFileSync(filepath, content, 'utf-8');
    modifiedCount++;
    modifiedFiles.push(relative(resolve(import.meta.dirname, '..'), filepath));
  }
}

console.log(`Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
