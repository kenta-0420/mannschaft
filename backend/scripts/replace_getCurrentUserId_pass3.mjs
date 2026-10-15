/**
 * 第3パス:
 * 1. 壊れたメソッド定義 (private Long SecurityUtils.getCurrentUserId()) を削除
 * 2. インライン Long userId = 0L; のTODO付きを置換
 * 3. 残存する全TODOコメント (JwtAuthenticationFilter/SecurityContext系) を削除
 * 4. Long userId = 0L; (TODOなしの残り) も SecurityUtils.getCurrentUserId() に置換
 */

import { readFileSync, writeFileSync } from 'fs';
import { glob } from 'fs/promises';
import { resolve, relative } from 'path';

const SRC_ROOT = resolve(import.meta.dirname, '..', 'src', 'main', 'java');
const IMPORT_LINE = 'import com.mannschaft.app.common.SecurityUtils;';

let modifiedCount = 0;
const modifiedFiles = [];

const pattern = resolve(SRC_ROOT, 'com/mannschaft/app/**/*.java');

for await (const filepath of glob(pattern)) {
  if (filepath.includes('.claude')) continue;

  let content = readFileSync(filepath, 'utf-8');
  const original = content;

  // 1. 壊れたメソッド定義を削除（TODO + private Long SecurityUtils.getCurrentUserId() + return 1L; + }）
  content = content.replace(
    /\n( +)\/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*\n\1private Long SecurityUtils\.getCurrentUserId\(\) \{\n\1    return 1L;\n\1\}\n/g,
    '\n'
  );

  // 2. インラインTODO + Long userId = 0L/1L; を SecurityUtils.getCurrentUserId() に置換
  content = content.replace(
    /^( +)\/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT|JwtAuthentication).*\n\1Long userId = [01]L;/gm,
    '$1Long userId = SecurityUtils.getCurrentUserId();'
  );

  // 3. 残存する全 JwtAuthenticationFilter/SecurityContext 系のTODOコメント行を削除
  content = content.replace(
    /^.*\/\/ TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT Filter|JwtAuthenticationFilter|currentTeamIdをセキュリティ).*\n/gm,
    ''
  );

  // 4. TODOなしの standalone Long userId = 0L; をcontroller内で SecurityUtils.getCurrentUserId() に
  //    ただしテストファイルなどは除外（controllerのみ対象）
  if (filepath.includes('controller')) {
    content = content.replace(
      /^( +)Long userId = 0L;$/gm,
      '$1Long userId = SecurityUtils.getCurrentUserId();'
    );
  }

  // import 追加（変更があった場合かつSecurityUtils呼び出しが含まれる場合）
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

console.log(`Pass 3: Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
