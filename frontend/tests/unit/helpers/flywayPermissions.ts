import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * Flyway migration の `INSERT INTO permissions` から権限カタログ（name, scope）を抜き出す。
 *
 * BE の番人 `PermissionNameCatalogGuardTest.parseCatalogFromSql` の仕様を TS へ移したもの
 * （F01.2.1 ALIC-2、陣立て書 r3 §3）。権限名の i18n 網羅番人
 * `tests/unit/i18n/permission-names-coverage.spec.ts` が、手書きの名前リストを持たずに
 * DB の正本と6言語の訳を突き合わせるために使う。
 *
 * 黙って読み飛ばすと「漏れ」を「網羅」と誤判定するため、次はすべて例外で止める:
 * - 未対応書式（SET 形式・REPLACE INTO 等、列リストを持たない INSERT）
 * - name / scope 列が無い・値が文字列リテラルでない
 * - scope が TEAM / ORGANIZATION / PLATFORM 以外
 * - 1文から1件も取れない
 * - コメント除去後の `INTO permissions (` 出現数と解析した文の数が一致しない
 */

export type PermissionScope = 'TEAM' | 'ORGANIZATION' | 'PLATFORM'

export interface FlywayPermission {
  name: string
  scope: PermissionScope
  file: string
}

const MIGRATION_DIR = resolve(__dirname, '../../../../backend/src/main/resources/db/migration')

const SCOPES: readonly PermissionScope[] = ['TEAM', 'ORGANIZATION', 'PLATFORM']

/** 行コメント（`-- `・`#`）とブロックコメントを除く。引用符の中は保持する。 */
export function stripSqlComments(sql: string): string {
  let out = ''
  let i = 0
  while (i < sql.length) {
    const c = sql[i]!
    if (c === '\'' || c === '"' || c === '`') {
      const end = skipQuoted(sql, i, c)
      out += sql.slice(i, end + 1)
      i = end + 1
    }
    else if (c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i++
    }
    else if (c === '#') {
      while (i < sql.length && sql[i] !== '\n') i++
    }
    else if (c === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2)
      i = end < 0 ? sql.length : end + 2
      out += ' '
    }
    else {
      out += c
      i++
    }
  }
  return out
}

/** `open` の引用符に対応する閉じ引用符の位置。`''` の二重化エスケープとバックスラッシュを扱う。 */
function skipQuoted(s: string, open: number, quote: string): number {
  let i = open + 1
  while (i < s.length) {
    const c = s[i]!
    if (c === '\\' && quote !== '`') {
      i += 2
      continue
    }
    if (c === quote) {
      if (s[i + 1] === quote) {
        i += 2
        continue
      }
      return i
    }
    i++
  }
  return s.length - 1
}

function matchingParen(s: string, open: number): number {
  let depth = 0
  for (let i = open; i < s.length; i++) {
    const c = s[i]!
    if (c === '\'' || c === '"' || c === '`') {
      i = skipQuoted(s, i, c)
      continue
    }
    if (c === '(') depth++
    else if (c === ')') {
      depth--
      if (depth === 0) return i
    }
  }
  return -1
}

function splitTopLevel(s: string): string[] {
  const parts: string[] = []
  let depth = 0
  let cur = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]!
    if (c === '\'' || c === '"' || c === '`') {
      const end = skipQuoted(s, i, c)
      cur += s.slice(i, end + 1)
      i = end
      continue
    }
    if (c === '(') depth++
    else if (c === ')') depth--
    if (c === ',' && depth === 0) {
      parts.push(cur.trim())
      cur = ''
    }
    else cur += c
  }
  if (cur.trim().length > 0) parts.push(cur.trim())
  return parts
}

function statementEnd(s: string, from: number): number {
  for (let i = from; i < s.length; i++) {
    const c = s[i]!
    if (c === '\'' || c === '"' || c === '`') {
      i = skipQuoted(s, i, c)
      continue
    }
    if (c === ';') return i
  }
  return s.length
}

function literal(elements: string[], idx: number, column: string, label: string): string {
  const raw = elements[idx]
  if (raw === undefined) throw new Error(`${label}: ${column} 列に対応する値が無い: ${elements.join(', ')}`)
  const v = raw.trim()
  if (v.length < 2 || !v.startsWith('\'') || !v.endsWith('\'')) {
    throw new Error(`${label}: permissions.${column} が文字列リテラルでない（未対応形式）: ${v}`)
  }
  return v.slice(1, -1).replace(/''/g, '\'')
}

function toPermission(elements: string[], nameIdx: number, scopeIdx: number, label: string): FlywayPermission {
  const name = literal(elements, nameIdx, 'name', label)
  const scope = literal(elements, scopeIdx, 'scope', label)
  if (!(SCOPES as readonly string[]).includes(scope)) {
    throw new Error(`${label}: permissions.scope が未知の値: ${scope}（${name}）`)
  }
  return { name, scope: scope as PermissionScope, file: label }
}

/** すべての `INSERT … INTO permissions`（列リストの有無を問わない）。未対応書式の検出に使う。 */
const ANY_INSERT = /\b(?:INSERT|REPLACE)\s+(?:IGNORE\s+)?INTO\s+`?permissions`?(?![\w])/gi
/** 対応書式の文頭。 */
const SUPPORTED_INSERT = /\bINSERT\s+(?:IGNORE\s+)?INTO\s+`?permissions`?\s*\(/gi
/** 突合用の出現数。 */
const INTO_PERMISSIONS_PAREN = /\bINTO\s+`?permissions`?\s*\(/gi

/**
 * 1 ファイル分の SQL を解析する。
 * 対応書式: INSERT INTO / INSERT IGNORE INTO、VALUES 単発・複数行、INSERT … SELECT … FROM DUAL WHERE NOT EXISTS。
 */
export function parsePermissionInserts(sqlRaw: string, label: string): FlywayPermission[] {
  const sql = stripSqlComments(sqlRaw)
  for (const m of sql.matchAll(ANY_INSERT)) {
    const rest = sql.slice((m.index ?? 0) + m[0].length)
    if (/^\s*\(/.test(rest) && /^INSERT/i.test(m[0])) continue
    throw new Error(`${label}: INSERT INTO permissions の未対応書式（列リスト付き INSERT 以外）: ${sql.slice(m.index ?? 0, (m.index ?? 0) + 80)}`)
  }
  const result: FlywayPermission[] = []
  let statements = 0
  for (const m of sql.matchAll(SUPPORTED_INSERT)) {
    statements++
    const columnsOpen = (m.index ?? 0) + m[0].length - 1
    const columnsClose = matchingParen(sql, columnsOpen)
    if (columnsClose < 0) throw new Error(`${label}: INSERT INTO permissions の列リストが閉じていない`)
    const columns = splitTopLevel(sql.slice(columnsOpen + 1, columnsClose)).map(c => c.replace(/`/g, '').trim().toLowerCase())
    const nameIdx = columns.indexOf('name')
    const scopeIdx = columns.indexOf('scope')
    if (nameIdx < 0) throw new Error(`${label}: INSERT INTO permissions に name 列が無い: ${columns.join(', ')}`)
    if (scopeIdx < 0) throw new Error(`${label}: INSERT INTO permissions に scope 列が無い: ${columns.join(', ')}`)
    const end = statementEnd(sql, columnsClose)
    const body = sql.slice(columnsClose + 1, end)
    const before = result.length
    const values = /\bVALUES\b/i.exec(body)
    if (values) {
      let i = values.index + values[0].length
      while (i < body.length) {
        const open = body.indexOf('(', i)
        if (open < 0) break
        const close = matchingParen(body, open)
        if (close < 0) throw new Error(`${label}: VALUES タプルが閉じていない`)
        result.push(toPermission(splitTopLevel(body.slice(open + 1, close)), nameIdx, scopeIdx, label))
        i = close + 1
        const next = body.slice(i).trimStart()
        if (!next.startsWith(',')) break
        i = body.indexOf(',', i) + 1
      }
    }
    else {
      const select = /\bSELECT\b/i.exec(body)
      if (!select) {
        throw new Error(`${label}: INSERT INTO permissions が VALUES / SELECT のいずれでもない未対応形式`)
      }
      const afterSelect = body.slice(select.index + select[0].length)
      const from = /\bFROM\b/i.exec(afterSelect)
      const list = splitTopLevel(from ? afterSelect.slice(0, from.index) : afterSelect)
      result.push(toPermission(list, nameIdx, scopeIdx, label))
    }
    if (result.length === before) throw new Error(`${label}: INSERT INTO permissions の1文から権限を1件も抽出できなかった`)
  }
  const occurrences = [...sql.matchAll(INTO_PERMISSIONS_PAREN)].length
  if (occurrences !== statements) {
    throw new Error(`${label}: INTO permissions ( の出現数 ${occurrences} と解析文数 ${statements} が一致しない`)
  }
  return result
}

/** 実 Flyway の全 migration から権限カタログを抜き出す。0件なら例外（パス誤り・書式変更の偽 PASS 防止）。 */
export function loadFlywayPermissions(dir: string = MIGRATION_DIR): FlywayPermission[] {
  const files = readdirSync(dir).filter(f => f.endsWith('.sql')).sort()
  const all = files.flatMap(f => parsePermissionInserts(readFileSync(resolve(dir, f), 'utf8'), f))
  if (all.length === 0) throw new Error(`Flyway から権限を1件も抽出できなかった: ${dir}`)
  return all
}
