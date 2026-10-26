/**
 * 最終クリーンアップ: 残存するすべてのユーザーID取得スタブメソッドを除去する。
 * パターン: getCurrentUserId(), getAuthenticatedUserId() いずれも対象。
 */

import { readFileSync, writeFileSync } from 'fs';
import { glob } from 'fs/promises';
import { resolve, relative } from 'path';

const SRC_ROOT = resolve(import.meta.dirname, '..', 'src', 'main', 'java');
const IMPORT_LINE = 'import com.mannschaft.app.common.SecurityUtils;';

let modifiedCount = 0;
const modifiedFiles = [];

const pattern = resolve(SRC_ROOT, 'com/mannschaft/app/**/*.java');

// スタブメソッドの名前パターン
const STUB_METHOD_NAMES = ['getCurrentUserId', 'getAuthenticatedUserId'];

for await (const filepath of glob(pattern)) {
  if (filepath.includes('.claude')) continue;

  let content = readFileSync(filepath, 'utf-8');

  // いずれかのメソッド名を含むファイルのみ処理
  if (!STUB_METHOD_NAMES.some(n => content.includes(n))) continue;

  const original = content;
  const eol = content.includes('\r\n') ? '\r\n' : '\n';
  const lines = content.split(/\r?\n/);
  const filtered = [];
  let i = 0;

  while (i < lines.length) {
    const trimmed = lines[i].trim();

    // スタブメソッドの開始行を検出
    const isStubMethod = STUB_METHOD_NAMES.some(name =>
      trimmed === `private Long ${name}() {`
    );

    if (isStubMethod) {
      // Javadoc/コメントブロックを遡って削除
      while (filtered.length > 0) {
        const prev = filtered[filtered.length - 1].trim();
        if (prev === '' || prev.startsWith('*') || prev.startsWith('/**') || prev.startsWith('*/') || prev.startsWith('// TODO')) {
          filtered.pop();
        } else {
          break;
        }
      }
      // メソッド本体をスキップ (return 1L; と })
      i++;
      while (i < lines.length) {
        const t = lines[i].trim();
        i++;
        if (t === '}') break;
      }
      continue;
    }

    filtered.push(lines[i]);
    i++;
  }

  content = filtered.join(eol);

  // メソッド呼び出しを SecurityUtils.getCurrentUserId() に置換
  for (const name of STUB_METHOD_NAMES) {
    content = content.replaceAll(`${name}()`, 'SecurityUtils.getCurrentUserId()');
  }

  // import 追加
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

console.log(`Cleanup: Modified ${modifiedCount} files:`);
modifiedFiles.forEach(f => console.log(`  ${f}`));
