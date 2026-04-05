/**
 * 第4パス（CRLF対応）:
 * 1. 壊れたメソッド定義を削除
 * 2. 残存TODO + Long userId = 0L/1L; を SecurityUtils に置換
 * 3. 残存TODOコメント行を削除
 */

import { readFileSync, writeFileSync } from 'fs';
import { glob } from 'fs/promises';
import { resolve, relative } from 'path';

const SRC_ROOT = resolve(import.meta.dirname, '..', 'src', 'main', 'java');
const IMPORT_LINE = 'import com.mannschaft.app.common.SecurityUtils;';

// \r?\n で CRLF と LF の両方に対応
const NL = '\\r?\\n';

let modifiedCount = 0;
const modifiedFiles = [];

const pattern = resolve(SRC_ROOT, 'com/mannschaft/app/**/*.java');

for await (const filepath of glob(pattern)) {
  if (filepath.includes('.claude')) continue;

  let content = readFileSync(filepath, 'utf-8');
  const original = content;

  // 1. 壊れたメソッド定義を削除
  const brokenMethodRe = new RegExp(
    `${NL}( +)// TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*${NL}\\1private Long SecurityUtils\\.getCurrentUserId\\(\\) \\{${NL}\\1    return 1L;${NL}\\1\\}${NL}`,
    'g'
  );
  content = content.replace(brokenMethodRe, (m) => m.includes('\r\n') ? '\r\n' : '\n');

  // 2. インラインTODO + Long userId = 0L/1L;
  const inlineTodoRe = new RegExp(
    `^( +)// TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*${NL}\\1Long userId = [01]L;`,
    'gm'
  );
  content = content.replace(inlineTodoRe, '$1Long userId = SecurityUtils.getCurrentUserId();');

  // 3. 全残存TODOコメント行を削除
  const todoLineRe = new RegExp(
    `^.*// TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT Filter|JwtAuthenticationFilter|currentTeamIdをセキュリティ).*${NL}`,
    'gm'
  );
  content = content.replace(todoLineRe, '');

  // 4. standalone Long userId = 0L; in controllers
  if (filepath.includes('controller')) {
    content = content.replace(/^( +)Long userId = 0L;$/gm, '$1Long userId = SecurityUtils.getCurrentUserId();');
  }

  // import 追加
  if (content !== original && content.includes('SecurityUtils.getCurrentUserId()') && !content.includes(IMPORT_LINE)) {
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

console.log(`Pass 4: Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
