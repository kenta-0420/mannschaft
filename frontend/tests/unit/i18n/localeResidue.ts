/**
 * 非 ja ロケールの日本語残存・構造不一致を検出する番人（CMP-261004-1944）の検査ロジック。
 *
 * 検査はすべて純関数とし、本物のロケール（locale-no-japanese-residue.spec.ts）と
 * 違反を1件だけ含む fixture（検出器の自己検査）の両方から同じ関数を呼ぶ。
 * 受け入れ条件の正本は台帳 CMP-261004-1944 の AC-1〜AC-10。
 */
import { existsSync, readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { resolve } from 'node:path'
import { baseCompile } from '@intlify/message-compiler'

export const BASE_LANG = 'ja'
export const TARGET_LANGS = ['en', 'de', 'es', 'ko', 'zh'] as const
export type TargetLang = (typeof TARGET_LANGS)[number]

/** 違反1件。失敗メッセージにはファイル・言語・キーパス・値を必ず出す。 */
export interface Violation {
  rule: string
  file: string
  lang: string
  path: string
  value: string
}

/** 1言語・1ファイル分の読み込み済みメッセージ。file は言語ディレクトリを除いた名前（例 common.json）。 */
export interface LocaleDoc {
  lang: string
  file: string
  messages: unknown
}

// ---------------------------------------------------------------------------
// 文字種の定義（AC 定義節）
// 仮名 = U+3040-309F と U+30A0-30FA, U+30FC-30FF（中黒 U+30FB は句読点として許容）
// 漢字 = U+4E00-9FFF
// ---------------------------------------------------------------------------
const KANA = /[぀-ゟ゠-ヺー-ヿ]/u
const KANJI = /[一-鿿]/u
const KANJI_GLOBAL = /[一-鿿]/gu

export function hasKana(s: string): boolean {
  return KANA.test(s)
}

export function hasKanaOrKanji(s: string): boolean {
  return KANA.test(s) || KANJI.test(s)
}

// ---------------------------------------------------------------------------
// GB2312 符号化可能な漢字集合（AC-3）
// 新規依存を足さないため、GB2312 の漢字領域（第1水準 0xB0-0xD7・第2水準 0xD8-0xF7、
// 下位 0xA1-0xFE）を Node の TextDecoder('gbk') で復号して集合を作る。
// GBK はこの領域で GB2312 と同一の写像を持つ。
// ---------------------------------------------------------------------------
let gb2312Kanji: Set<string> | null = null

export function gb2312KanjiSet(): Set<string> {
  if (gb2312Kanji) return gb2312Kanji
  const decoder = new TextDecoder('gbk', { fatal: false })
  const set = new Set<string>()
  for (let hi = 0xb0; hi <= 0xf7; hi++) {
    for (let lo = 0xa1; lo <= 0xfe; lo++) {
      const ch = decoder.decode(new Uint8Array([hi, lo]))
      if (ch.length === 1 && KANJI.test(ch)) set.add(ch)
    }
  }
  // 復号器が GBK 非対応（small-icu 等）なら集合が空になり全漢字が違反になる偽赤を招く。
  // GB2312 の漢字は 6,763 字。大きく外れたら検査の前提が壊れているので明示的に落とす。
  if (set.size < 6700) {
    throw new Error(`GB2312 漢字集合の構築に失敗（${set.size} 字）。Node の ICU が GBK 復号に対応していない可能性`)
  }
  gb2312Kanji = set
  return set
}

export function nonGb2312Kanji(s: string): string[] {
  const set = gb2312KanjiSet()
  return [...new Set(s.match(KANJI_GLOBAL) ?? [])].filter((c) => !set.has(c))
}

// ---------------------------------------------------------------------------
// 木の走査
// ---------------------------------------------------------------------------
export type NodeType = 'string' | 'number' | 'boolean' | 'null' | 'array' | 'object' | 'other'

export function nodeType(v: unknown): NodeType {
  if (v === null) return 'null'
  if (Array.isArray(v)) return 'array'
  switch (typeof v) {
    case 'string':
      return 'string'
    case 'number':
      return 'number'
    case 'boolean':
      return 'boolean'
    case 'object':
      return 'object'
    default:
      return 'other'
  }
}

function childPath(prefix: string, key: string | number): string {
  if (typeof key === 'number') return `${prefix}[${key}]`
  return prefix ? `${prefix}.${key}` : key
}

/** 全ノード（ルート除く）を パス→値 で返す。配列内も再帰する。 */
export function allNodes(tree: unknown): Map<string, unknown> {
  const out = new Map<string, unknown>()
  const walk = (v: unknown, prefix: string): void => {
    if (Array.isArray(v)) {
      v.forEach((c, i) => {
        const p = childPath(prefix, i)
        out.set(p, c)
        walk(c, p)
      })
    } else if (v !== null && typeof v === 'object') {
      for (const [k, c] of Object.entries(v as Record<string, unknown>)) {
        const p = childPath(prefix, k)
        out.set(p, c)
        walk(c, p)
      }
    }
  }
  walk(tree, '')
  return out
}

/** 文字列の葉だけを パス→値 で返す。 */
export function stringLeaves(tree: unknown): Map<string, string> {
  const out = new Map<string, string>()
  for (const [p, v] of allNodes(tree)) if (typeof v === 'string') out.set(p, v)
  return out
}

// ---------------------------------------------------------------------------
// 許容リスト・基準時点の不一致リスト
// ---------------------------------------------------------------------------
/** AC-1/AC-3 の例外。意図的に日本語（または GB2312 外の字）であるべき値だけを載せる。 */
export interface AllowEntry {
  rule: string
  lang: string
  file: string
  path: string
  reason: string
}

/**
 * AC-4/AC-9c の基準時点（origin/main da74b6dab6）で既に存在した不一致と、
 * AC-9b の「語順の都合で接頭辞・接尾辞が空になるのが正しいキー」（殿裁定）。
 */
export interface BaselineEntry {
  rule: string
  lang: string
  file: string
  path: string
  reason: string
}

function entryKey(e: { rule: string; lang: string; file: string; path: string }): string {
  return `${e.rule}\u0000${e.lang}\u0000${e.file}\u0000${e.path}`
}

/**
 * 違反から許容（または基準時点の既存）分を除き、残った違反と未使用の許容行を返す。
 * 未使用の行は「直したのに台帳に残った例外」であり、放置すると後で同じ箇所の再劣化を黙認するため失敗とする。
 */
export function applyExemptions<E extends { rule: string; lang: string; file: string; path: string }>(
  violations: Violation[],
  entries: E[],
): { remaining: Violation[]; unused: E[] } {
  const keys = new Map(entries.map((e) => [entryKey(e), e]))
  const used = new Set<string>()
  const remaining: Violation[] = []
  for (const v of violations) {
    const k = entryKey(v)
    if (keys.has(k)) used.add(k)
    else remaining.push(v)
  }
  return { remaining, unused: entries.filter((e) => !used.has(entryKey(e))) }
}

// ---------------------------------------------------------------------------
// AC-1〜AC-9 の検査（各関数は1言語・1ファイル分。ja は比較の正本）
// ---------------------------------------------------------------------------
function v(rule: string, doc: LocaleDoc, path: string, value: unknown): Violation {
  return { rule, file: doc.file, lang: doc.lang, path, value: typeof value === 'string' ? value : JSON.stringify(value) }
}

/** AC-1: en/de/es/ko の全文字列値に仮名・漢字が無い。 */
export function checkNoJapaneseScript(doc: LocaleDoc): Violation[] {
  if (doc.lang === 'zh' || doc.lang === BASE_LANG) return []
  return [...stringLeaves(doc.messages)].filter(([, s]) => hasKanaOrKanji(s)).map(([p, s]) => v('AC-1', doc, p, s))
}

/** AC-2: zh の全文字列値に仮名が無い。 */
export function checkZhNoKana(doc: LocaleDoc): Violation[] {
  if (doc.lang !== 'zh') return []
  return [...stringLeaves(doc.messages)].filter(([, s]) => hasKana(s)).map(([p, s]) => v('AC-2', doc, p, s))
}

/** AC-3: zh の全文字列値の漢字が GB2312 で符号化できる。 */
export function checkZhGb2312(doc: LocaleDoc): Violation[] {
  if (doc.lang !== 'zh') return []
  return [...stringLeaves(doc.messages)].filter(([, s]) => nonGb2312Kanji(s).length > 0).map(([p, s]) => v('AC-3', doc, p, s))
}

/** AC-4: キー集合（全ノードのパス集合）が ja と一致する。missing/extra の両方を違反とする。 */
export function checkKeySet(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = allNodes(ja.messages)
  const mine = allNodes(doc.messages)
  const out: Violation[] = []
  for (const [p, val] of base) if (!mine.has(p)) out.push(v('AC-4', doc, p, `(missing; ja=${JSON.stringify(val)})`))
  for (const [p, val] of mine) if (!base.has(p)) out.push(v('AC-4', doc, p, `(extra) ${JSON.stringify(val)}`))
  return out
}

/** 波括弧の中身を除いた本文（リテラル補間 {'|'} 等を区切りと誤認しないため）。 */
function stripInterpolations(s: string): string {
  return s.replace(/\{[^{}]*\}/g, '')
}

/** 名前付き・リスト形式のプレースホルダ名集合（リテラル補間 {'x'} は除く）。 */
export function placeholderNames(s: string): Set<string> {
  const names = new Set<string>()
  for (const m of s.matchAll(/\{\s*([^{}]*?)\s*\}/g)) {
    const inner = m[1] ?? ''
    if (/^'.*'$/.test(inner)) continue
    names.add(inner)
  }
  return names
}

function sameSet(a: Set<string>, b: Set<string>): boolean {
  return a.size === b.size && [...a].every((x) => b.has(x))
}

/** AC-5: 各キーのプレースホルダ集合が ja と一致する（両方に存在する文字列キーのみ比較）。 */
export function checkPlaceholders(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = stringLeaves(ja.messages)
  const out: Violation[] = []
  for (const [p, s] of stringLeaves(doc.messages)) {
    const b = base.get(p)
    if (b === undefined) continue
    const want = placeholderNames(b)
    const got = placeholderNames(s)
    if (!sameSet(want, got)) out.push(v('AC-5', doc, p, `${s}  (ja={${[...want].join(',')}} / ${doc.lang}={${[...got].join(',')}})`))
  }
  return out
}

export function pluralSeparatorCount(s: string): number {
  return (stripInterpolations(s).match(/\|/g) ?? []).length
}

/**
 * AC-6（殿裁定で改定）: ja の区切り | が1以上のキーに限り、数が ja と一致する。
 * ja に複数形が無いキーへ en/de/es 等が文法上の複数形を入れるのは正しい訳なので許す（構文の正しさは AC-7 が守る）。
 */
export function checkPluralSeparators(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = stringLeaves(ja.messages)
  const out: Violation[] = []
  for (const [p, s] of stringLeaves(doc.messages)) {
    const b = base.get(p)
    if (b === undefined) continue
    const want = pluralSeparatorCount(b)
    if (want === 0) continue
    const got = pluralSeparatorCount(s)
    if (want !== got) out.push(v('AC-6', doc, p, `${s}  (ja=${want} / ${doc.lang}=${got})`))
  }
  return out
}

/** AC-7: 全メッセージが @intlify/message-compiler でエラーなくコンパイルできる（ja も対象）。 */
export function checkCompiles(doc: LocaleDoc): Violation[] {
  const out: Violation[] = []
  for (const [p, s] of stringLeaves(doc.messages)) {
    const errors: string[] = []
    try {
      baseCompile(s, { onError: (e: Error) => errors.push(e.message) })
    } catch (e) {
      errors.push(e instanceof Error ? e.message : String(e))
    }
    if (errors.length > 0) out.push(v('AC-7', doc, p, `${s}  (${errors.join(' / ')})`))
  }
  return out
}

/** AC-8: es のアクセント欠落綴り辞書（語境界・大小無視）。「mas」は語として 0 件（「más」に）。 */
export const ES_ACCENT_MISSING_WORDS = [
  'contrasena', 'electronico', 'sesion', 'publico', 'organizacion', 'pagina', 'automaticamente',
  'configuracion', 'aplicacion', 'tambien', 'accion', 'telefono', 'numero', 'codigo', 'informacion',
  'notificacion', 'descripcion', 'direccion', 'invitacion', 'ubicacion', 'creacion', 'actualizacion',
  'suscripcion', 'verificacion', 'autenticacion', 'categoria', 'ultimo', 'metodo', 'anadir', 'opcion',
  'seccion', 'eliminacion', 'valido', 'aqui', 'despues', 'mas',
] as const

const ES_ACCENT_RE = new RegExp(
  `(?<![\\p{L}\\p{N}_])(${ES_ACCENT_MISSING_WORDS.join('|')})(?![\\p{L}\\p{N}_])`,
  'iu',
)

export function findEsAccentMissing(s: string): string | null {
  return ES_ACCENT_RE.exec(s)?.[1] ?? null
}

export function checkEsAccents(doc: LocaleDoc): Violation[] {
  if (doc.lang !== 'es') return []
  return [...stringLeaves(doc.messages)]
    .map(([p, s]) => [p, s, findEsAccentMissing(s)] as const)
    .filter(([, , w]) => w !== null)
    .map(([p, s, w]) => v('AC-8', doc, p, `${s}  (word=${w})`))
}

/** AC-9a: ja で空文字の値は他言語でも空文字。 */
export function checkEmptyStaysEmpty(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = stringLeaves(ja.messages)
  const out: Violation[] = []
  for (const [p, s] of stringLeaves(doc.messages)) {
    if (base.get(p) === '' && s !== '') out.push(v('AC-9a', doc, p, s))
  }
  return out
}

/** AC-9b: ja で trim 後非空の値は他言語でも trim 後非空。 */
export function checkNonEmptyStaysNonEmpty(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = stringLeaves(ja.messages)
  const out: Violation[] = []
  for (const [p, s] of stringLeaves(doc.messages)) {
    const b = base.get(p)
    if (b !== undefined && b.trim() !== '' && s.trim() === '') out.push(v('AC-9b', doc, p, s))
  }
  return out
}

/** AC-9c: 値の型と配列長が ja と一致する（両方に存在するパスのみ比較。欠落は AC-4 が扱う）。 */
export function checkTypesAndLengths(ja: LocaleDoc, doc: LocaleDoc): Violation[] {
  const base = allNodes(ja.messages)
  const out: Violation[] = []
  for (const [p, val] of allNodes(doc.messages)) {
    if (!base.has(p)) continue
    const b = base.get(p)
    const bt = nodeType(b)
    const t = nodeType(val)
    if (bt !== t) {
      out.push(v('AC-9c', doc, p, `(type ja=${bt} / ${doc.lang}=${t}) ${JSON.stringify(val)}`))
    } else if (t === 'array' && (b as unknown[]).length !== (val as unknown[]).length) {
      out.push(v('AC-9c', doc, p, `(length ja=${(b as unknown[]).length} / ${doc.lang}=${(val as unknown[]).length})`))
    }
  }
  return out
}

// ---------------------------------------------------------------------------
// 対象ファイルの列挙と読み込み（AC-10）
// ---------------------------------------------------------------------------
/**
 * nuxt.config.ts の i18n `locales: [...]` から 言語コード→登録ファイル一覧 を抜き出す。
 * nuxt.config.ts は defineNuxtConfig（Nuxt 自動 import）や多数のモジュールに依存し単体 import できないため、
 * 文字列から抜く。抜けなかった・空だったときは例外にする（対象0件の空振り green を防ぐ）。
 */
export function parseRegisteredLocaleFiles(configText: string): Map<string, string[]> {
  const start = configText.indexOf('locales: [')
  if (start < 0) throw new Error('nuxt.config.ts に i18n の `locales: [` が見つからない')
  // 角括弧の対応で locales 配列の終端を求め、その範囲だけを見る（他設定の `code:` を拾わないため）
  const open = configText.indexOf('[', start)
  let depth = 0
  let end = -1
  for (let i = open; i < configText.length; i++) {
    const c = configText[i]
    if (c === '[') depth++
    else if (c === ']' && --depth === 0) {
      end = i
      break
    }
  }
  if (end < 0) throw new Error('nuxt.config.ts の i18n `locales` 配列の終端が見つからない')
  const out = new Map<string, string[]>()
  const body = configText.slice(open, end + 1)
  for (const m of body.matchAll(/\{\s*code:\s*'([^']+)'[\s\S]*?files:\s*\[([\s\S]*?)\]/g)) {
    const code = m[1] as string
    if (out.has(code)) break
    const files = [...(m[2] as string).matchAll(/'([^']+)'/g)].map((x) => x[1] as string)
    out.set(code, files)
  }
  if (out.size === 0) throw new Error('nuxt.config.ts から登録ロケールを1件も抜き出せなかった')
  for (const [code, files] of out) {
    if (files.length === 0) throw new Error(`nuxt.config.ts の ${code} の files が空`)
  }
  return out
}

/** UTF-8 BOM を剥がして読む（BOM 付き JSON は JSON.parse が落ちるため）。 */
export function readTextStripBom(path: string): string {
  const raw = readFileSync(path, 'utf-8')
  return raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw
}

/**
 * TS ロケールの default export を評価する。
 *
 * vitest の import() で読むと、Nuxt 設定由来の @intlify/unplugin-vue-i18n が langDir 配下の
 * ロケールを AST へ事前コンパイルして返すため、文字列のはずの値がオブジェクトになり全検査が狂う
 * （実測: recruitment.ts のプレースホルダ付き文字列が {type,start,end,loc,...} に化けた）。
 * よってソースを直接読み、型注釈を Node の stripTypeScriptTypes で剥がしてから評価する。
 * import 文を持つ等で評価できなければ例外（＝パース失敗として赤）にする。
 */
export function evaluateTsDefaultExport(source: string, label: string): unknown {
  const js = stripTypeScriptTypes(source)
  if (!/^\s*export\s+default\s+/m.test(js)) throw new Error(`default export が無い: ${label}`)
  const body = js.replace(/^\s*export\s+default\s+/m, 'return ')
  const value: unknown = new Function(body)()
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`default export がオブジェクトでない: ${label}`)
  }
  return value
}

/** 1ファイルを読み込む。JSON は BOM 除去してパース、TS は default export を評価する。不在・失敗は例外。 */
export async function loadLocaleFile(absPath: string): Promise<unknown> {
  if (!existsSync(absPath)) throw new Error(`ロケールファイルが存在しない: ${absPath}`)
  if (absPath.endsWith('.json')) return JSON.parse(readTextStripBom(absPath))
  if (absPath.endsWith('.ts')) return evaluateTsDefaultExport(readTextStripBom(absPath), absPath)
  throw new Error(`未対応のロケールファイル形式: ${absPath}`)
}

export interface LoadedLocales {
  /** ファイル名（例 common.json）→ 言語 → 文書 */
  byFile: Map<string, Map<string, LocaleDoc>>
  /** 読み込みに失敗した（不在・パース失敗・登録漏れ）理由の一覧 */
  errors: string[]
}

/** 登録一覧に従って ja と対象5言語を読む。ja に登録があり他言語に無いファイルもエラーとして返す。 */
export async function loadRegisteredLocales(localesDir: string, registered: Map<string, string[]>): Promise<LoadedLocales> {
  const errors: string[] = []
  const byFile = new Map<string, Map<string, LocaleDoc>>()
  const langs = [BASE_LANG, ...TARGET_LANGS]
  for (const lang of langs) {
    if (!registered.has(lang)) errors.push(`nuxt.config.ts に ${lang} の登録が無い`)
  }
  const baseFiles = (registered.get(BASE_LANG) ?? []).map((f) => f.replace(/^[^/]+\//, ''))
  if (baseFiles.length === 0) errors.push('ja の登録ファイルが0件')
  for (const lang of langs) {
    const files = registered.get(lang) ?? []
    const names = files.map((f) => f.replace(/^[^/]+\//, ''))
    for (const name of baseFiles) if (!names.includes(name)) errors.push(`${lang}: ${name} が nuxt.config.ts に未登録（ja には登録あり）`)
    for (const name of names) if (!baseFiles.includes(name)) errors.push(`${lang}: ${name} は ja に未登録`)
    for (const rel of files) {
      const name = rel.replace(/^[^/]+\//, '')
      try {
        const messages = await loadLocaleFile(resolve(localesDir, rel))
        if (!byFile.has(name)) byFile.set(name, new Map())
        byFile.get(name)!.set(lang, { lang, file: name, messages })
      } catch (e) {
        errors.push(`${lang}/${name}: ${e instanceof Error ? e.message : String(e)}`)
      }
    }
  }
  return { byFile, errors }
}

// ---------------------------------------------------------------------------
// 規則の束ね
// ---------------------------------------------------------------------------
export type SingleCheck = (doc: LocaleDoc) => Violation[]
export type PairCheck = (ja: LocaleDoc, doc: LocaleDoc) => Violation[]

/** 読み込み済みロケール全体に規則を当てる。ja を除く5言語が対象（AC-7 のみ ja も含めて呼び出し側で指定）。 */
export function runSingle(loaded: LoadedLocales, check: SingleCheck, includeBase = false): Violation[] {
  const out: Violation[] = []
  for (const docs of loaded.byFile.values()) {
    for (const doc of docs.values()) {
      if (doc.lang === BASE_LANG && !includeBase) continue
      out.push(...check(doc))
    }
  }
  return out
}

export function runPair(loaded: LoadedLocales, check: PairCheck): Violation[] {
  const out: Violation[] = []
  for (const docs of loaded.byFile.values()) {
    const ja = docs.get(BASE_LANG)
    if (!ja) continue
    for (const doc of docs.values()) {
      if (doc.lang === BASE_LANG) continue
      out.push(...check(ja, doc))
    }
  }
  return out
}

/** 言語別件数（例 { en: 537, de: 1364 }）。 */
export function countByLang(violations: Violation[]): Record<string, number> {
  const out: Record<string, number> = {}
  for (const x of violations) out[x.lang] = (out[x.lang] ?? 0) + 1
  return out
}

/** 失敗メッセージ。総数・言語別件数と先頭 limit 件を ファイル・言語・キーパス・値 で出す。 */
export function formatViolations(title: string, violations: Violation[], limit = 40): string {
  const head = violations
    .slice(0, limit)
    .map((x) => `  [${x.rule}] ${x.lang}/${x.file} ${x.path} = ${truncate(x.value, 120)}`)
  const more = violations.length > limit ? [`  ...ほか ${violations.length - limit} 件`] : []
  return [`${title}: 総数 ${violations.length} 件 ${JSON.stringify(countByLang(violations))}`, ...head, ...more].join('\n')
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s
}
