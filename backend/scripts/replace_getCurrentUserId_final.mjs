/**
 * 最終パス: 残存する壊れたメソッド定義を全て削除する。
 * パターン: private Long SecurityUtils.getCurrentUserId() { return 1L; }
 * TODOコメント有無に関わらず全て対象。
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
  if (!content.includes('private Long SecurityUtils.getCurrentUserId()')) continue;

  const original = content;

  // 壊れたメソッド定義を行単位で削除（CRLF/LF対応）
  const lines = content.split(/\r?\n/);
  const eol = content.includes('\r\n') ? '\r\n' : '\n';
  const filtered = [];
  let i = 0;

  while (i < lines.length) {
    const trimmed = lines[i].trim();

    // 壊れたメソッド定義の開始行を検出
    if (trimmed === 'private Long SecurityUtils.getCurrentUserId() {') {
      // 次の2行 (return 1L; と }) をスキップ
      let skip = 1;
      while (i + skip < lines.length && skip <= 3) {
        const nextTrimmed = lines[i + skip].trim();
        if (nextTrimmed === 'return 1L;' || nextTrimmed === '}') {
          skip++;
        } else {
          break;
        }
      }
      // 前の空行も削除
      if (filtered.length > 0 && filtered[filtered.length - 1].trim() === '') {
        filtered.pop();
      }
      i += skip;
      continue;
    }

    filtered.push(lines[i]);
    i++;
  }

  content = filtered.join(eol);

  // import 追加（SecurityUtils呼び出しが含まれかつimportがない場合）
  if (content.includes('SecurityUtils.getCurrentUserId()') && !content.includes(IMPORT_LINE)) {
    const lastImportIdx = content.lastIndexOf(eol + 'import ');
    if (lastImportIdx !== -1) {
      const lineEnd = content.indexOf(eol, lastImportIdx + 1);
      content = content.slice(0, lineEnd + eol.length) + IMPORT_LINE + eol + content.slice(lineEnd + eol.length);
    }
  }

  if (content !== original) {
    writeFileSync(filepath, content, 'utf-8');
    modifiedCount++;
    modifiedFiles.push(relative(resolve(import.meta.dirname, '..'), filepath));
  }
}

console.log(`Final pass: Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
