/**
 * 第2パス: 残存するインラインTODOパターンを処理する。
 *
 * パターン1: TODOコメント + Long userId = 1L/0L; → SecurityUtils.getCurrentUserId() に置換
 * パターン2: 孤立TODOコメント（メソッド定義削除後の残り）→ 削除
 * パターン3: currentTeamId の TODO → コメントだけ削除
 */

import { readFileSync, writeFileSync } from 'fs';
import { glob } from 'fs/promises';
import { resolve, relative, basename } from 'path';

const SRC_ROOT = resolve(import.meta.dirname, '..', 'src', 'main', 'java');
const IMPORT_LINE = 'import com.mannschaft.app.common.SecurityUtils;';

// パターン1: TODO + Long userId = 1L/0L; → replace with SecurityUtils call
const INLINE_TODO_USERID = /^( +)\/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*\n\1Long userId = [01]L;/gm;

// パターン2: 孤立TODOコメント行（メソッド内やフィールド前に残ったもの）
const ORPHAN_TODO = /^ +\/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*\n/gm;

// パターン3: currentTeamIdのTODO
const CURRENT_TEAM_TODO = /^ +\/\/ TODO:.*currentTeamId.*セキュリティコンテキスト.*\n/gm;

let modifiedCount = 0;
const modifiedFiles = [];

const pattern = resolve(SRC_ROOT, 'com/mannschaft/app/**/*.java');

for await (const filepath of glob(pattern)) {
  if (filepath.includes('.claude')) continue;

  let content = readFileSync(filepath, 'utf-8');

  const original = content;

  // パターン1: インライン userId = 0L/1L を SecurityUtils.getCurrentUserId() に
  content = content.replace(INLINE_TODO_USERID, (match, indent) => {
    return `${indent}Long userId = SecurityUtils.getCurrentUserId();`;
  });

  // パターン2: 孤立TODOコメント削除
  content = content.replace(ORPHAN_TODO, '');

  // パターン3: currentTeamId TODO削除
  content = content.replace(CURRENT_TEAM_TODO, '');

  // import 追加（変更があった場合）
  if (content !== original && !content.includes(IMPORT_LINE)) {
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

console.log(`Pass 2: Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
