// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import {
  applyExemptions,
  checkCompiles,
  checkEmptyStaysEmpty,
  checkEsAccents,
  checkKeySet,
  checkNoJapaneseScript,
  checkNonEmptyStaysNonEmpty,
  checkPlaceholders,
  checkPluralSeparators,
  checkTypesAndLengths,
  checkZhGb2312,
  checkZhNoKana,
  formatViolations,
  loadRegisteredLocales,
  parseRegisteredLocaleFiles,
  readTextStripBom,
  runPair,
  runSingle,
  TARGET_LANGS,
  BASE_LANG,
  type AllowEntry,
  type BaselineEntry,
  type LoadedLocales,
  type LocaleDoc,
  type Violation,
} from './localeResidue'

/**
 * 非 ja ロケールの日本語残存・構造不一致の番人（CMP-261004-1944 AC-1〜AC-10）。
 *
 * 対象は frontend/nuxt.config.ts の i18n locales[].files に登録された全ファイル（*.json と recruitment.ts）。
 * ja が正本で、en/de/es/ko/zh を検査する。
 *
 * 2系統のテストを持つ:
 *  (a) 本物のロケール全体に対する検査 — 翻訳が入るまで red が正しい
 *  (b) AC ごとに違反を1件だけ含む fixture に対する検出器の自己検査 — 常に green
 *
 * 例外:
 *  - locale-residue-allowlist.json: AC-1/AC-3 の例外。意図的に日本語であるべき値（言語の自称など）だけを載せる。
 *    未翻訳の残存を載せてはならない（凍結は負債）。
 *  - locale-residue-baseline.json: AC-4/AC-9c の基準時点（origin/main da74b6dab6）で既に存在した不一致と、
 *    AC-9b の「語順の都合で接頭辞・接尾辞が空になるのが正しいキー」（例 en の timetable.period_suffix）。
 *    これ以外の不一致を増やさない。
 *  いずれも未使用の行があれば失敗する（直った例外を台帳に残さない）。
 */
const here = dirname(fileURLToPath(import.meta.url))
const frontendDir = resolve(here, '../../..')
const localesDir = resolve(frontendDir, 'app/locales')
const nuxtConfigPath = resolve(frontendDir, 'nuxt.config.ts')

const allowlist = JSON.parse(readTextStripBom(resolve(here, 'locale-residue-allowlist.json'))) as AllowEntry[]
const baseline = JSON.parse(readTextStripBom(resolve(here, 'locale-residue-baseline.json'))) as BaselineEntry[]

let loaded: LoadedLocales
let registered: Map<string, string[]>

beforeAll(async () => {
  registered = parseRegisteredLocaleFiles(readTextStripBom(nuxtConfigPath))
  loaded = await loadRegisteredLocales(localesDir, registered)
}, 120_000)

function expectNone(title: string, violations: Violation[]): void {
  expect(violations.length, formatViolations(title, violations)).toBe(0)
}

/** 許容リストを当てた残りの違反。 */
function afterAllowlist(violations: Violation[]): Violation[] {
  return applyExemptions(violations, allowlist).remaining
}

/** 基準時点リストを当てた残りの違反。 */
function afterBaseline(violations: Violation[]): Violation[] {
  return applyExemptions(violations, baseline).remaining
}

// ===========================================================================
// (a) 本物のロケール
// ===========================================================================
describe('対象の列挙と読み込み（AC-10）', () => {
  it('nuxt.config.ts に ja と対象5言語が登録され、各言語に1件以上のファイルがある', () => {
    const counts = Object.fromEntries([BASE_LANG, ...TARGET_LANGS].map((l) => [l, registered.get(l)?.length ?? 0]))
    expect(Object.values(counts).every((n) => n > 0), JSON.stringify(counts)).toBe(true)
  })

  it('登録された全ファイルが存在しパースでき、各言語の登録ファイル名が ja と一致する', () => {
    expect(loaded.errors, loaded.errors.join('\n')).toEqual([])
  })

  it('検査対象の文書が ja の登録ファイル数×6言語ぶん読み込まれている', () => {
    const docs = [...loaded.byFile.values()].reduce((n, m) => n + m.size, 0)
    expect(docs).toBe((registered.get(BASE_LANG)?.length ?? 0) * (1 + TARGET_LANGS.length))
  })
})

describe('本物のロケール: 日本語の残存（AC-1〜AC-3）', () => {
  it('AC-1: en/de/es/ko の全文字列値に仮名・漢字が無い（許容リストを除く）', () => {
    expectNone('AC-1 仮名・漢字の残存', afterAllowlist(runSingle(loaded, checkNoJapaneseScript)))
  })

  it('AC-2: zh の全文字列値に仮名が無い', () => {
    expectNone('AC-2 zh の仮名', runSingle(loaded, checkZhNoKana))
  })

  it('AC-3: zh の全文字列値の漢字が GB2312 で符号化できる（許容リストを除く）', () => {
    expectNone('AC-3 zh の GB2312 外の字', afterAllowlist(runSingle(loaded, checkZhGb2312)))
  })

  it('許容リストの全行が理由を持ち、AC-1/AC-3 のいずれかを対象とする', () => {
    const bad = allowlist.filter((e) => !['AC-1', 'AC-3'].includes(e.rule) || !e.reason?.trim())
    expect(bad).toEqual([])
  })

  it('許容リストに未使用の行が無い', () => {
    const violations = [...runSingle(loaded, checkNoJapaneseScript), ...runSingle(loaded, checkZhGb2312)]
    expect(applyExemptions(violations, allowlist).unused).toEqual([])
  })
})

describe('本物のロケール: 構造の一致（AC-4〜AC-9）', () => {
  it('AC-4: 各言語・各ファイルのキー集合が ja と一致する（基準時点の既存不一致を除く）', () => {
    expectNone('AC-4 キー集合の不一致', afterBaseline(runPair(loaded, checkKeySet)))
  })

  it('AC-5: 各キーのプレースホルダ集合が ja と一致する', () => {
    expectNone('AC-5 プレースホルダの不一致', runPair(loaded, checkPlaceholders))
  })

  it('AC-6: ja に複数形区切り | があるキーは、その数が ja と一致する', () => {
    expectNone('AC-6 複数形区切りの数の不一致', runPair(loaded, checkPluralSeparators))
  })

  it('AC-7: 全言語の全メッセージが @intlify/message-compiler でエラーなくコンパイルできる', () => {
    expectNone('AC-7 コンパイルエラー', runSingle(loaded, checkCompiles, true))
  })

  it('AC-8: es の値にアクセント欠落の綴りが無い', () => {
    expectNone('AC-8 es アクセント欠落', runSingle(loaded, checkEsAccents))
  })

  it('AC-9a: ja で空文字の値は他言語でも空文字', () => {
    expectNone('AC-9a 空文字の不一致', runPair(loaded, checkEmptyStaysEmpty))
  })

  it('AC-9b: ja で trim 後非空の値は他言語でも trim 後非空（語順の都合で空が正しいキーを除く）', () => {
    expectNone('AC-9b 空値化', afterBaseline(runPair(loaded, checkNonEmptyStaysNonEmpty)))
  })

  it('AC-9c: 値の型と配列長が ja と一致する（基準時点の既存不一致を除く）', () => {
    expectNone('AC-9c 型・配列長の不一致', afterBaseline(runPair(loaded, checkTypesAndLengths)))
  })

  it('基準時点リストの全行が理由を持ち、AC-4/AC-9b/AC-9c のいずれかを対象とする', () => {
    const bad = baseline.filter((e) => !['AC-4', 'AC-9b', 'AC-9c'].includes(e.rule) || !e.reason?.trim())
    expect(bad).toEqual([])
  })

  it('基準時点リストに未使用の行が無い', () => {
    const violations = [
      ...runPair(loaded, checkKeySet),
      ...runPair(loaded, checkNonEmptyStaysNonEmpty),
      ...runPair(loaded, checkTypesAndLengths),
    ]
    expect(applyExemptions(violations, baseline).unused).toEqual([])
  })
})

// ===========================================================================
// (b) 検出器の自己検査（AC ごとに違反を1件だけ含む fixture）
// ===========================================================================
function doc(lang: string, messages: unknown, file = 'fixture.json'): LocaleDoc {
  return { lang, file, messages }
}

const JA = doc('ja', { a: { title: 'タイトル', count: '{n} 件', plural: 'なし | 1件 | {n}件', empty: '', list: ['一', '二'] } })
const CLEAN_EN = { a: { title: 'Title', count: '{n} items', plural: 'none | one | {n} items', empty: '', list: ['one', 'two'] } }

describe('検出器の自己検査: AC-1〜AC-3', () => {
  it('AC-1: en の値に残った仮名を1件検出する（配列内も再帰する）', () => {
    const v = checkNoJapaneseScript(doc('en', { a: { title: 'Title', list: ['one', 'ふたつ'] } }))
    expect(v.map((x) => x.path)).toEqual(['a.list[1]'])
  })

  it('AC-1: ko の値に残った漢字を1件検出する', () => {
    const v = checkNoJapaneseScript(doc('ko', { a: { title: '제목', note: '保存' } }))
    expect(v.map((x) => x.path)).toEqual(['a.note'])
  })

  it('AC-1: 中黒（U+30FB）だけの値は違反にしない', () => {
    expect(checkNoJapaneseScript(doc('de', { a: 'A・B' }))).toEqual([])
  })

  it('AC-2: zh の値に残った仮名を1件検出する', () => {
    const v = checkZhNoKana(doc('zh', { a: '保存', b: 'キャンセル' }))
    expect(v.map((x) => x.path)).toEqual(['b'])
  })

  it('AC-3: zh の値の GB2312 で符号化できない字（日本の字体）を1件検出する', () => {
    const v = checkZhGb2312(doc('zh', { a: '设置', b: '設定' }))
    expect(v.map((x) => x.path)).toEqual(['b'])
  })

  it('許容リストに載った違反は除外され、使われなかった行は未使用として返る', () => {
    const violations = checkNoJapaneseScript(doc('en', { lang: { ja: '日本語' } }))
    const entries: AllowEntry[] = [
      { rule: 'AC-1', lang: 'en', file: 'fixture.json', path: 'lang.ja', reason: '自称' },
      { rule: 'AC-1', lang: 'en', file: 'fixture.json', path: 'lang.gone', reason: '既に消えたキー' },
    ]
    const r = applyExemptions(violations, entries)
    expect({ remaining: r.remaining.length, unused: r.unused.map((e) => e.path) }).toEqual({ remaining: 0, unused: ['lang.gone'] })
  })
})

describe('検出器の自己検査: AC-4〜AC-9', () => {
  it('AC-4: ja にあって他言語に無いキーを1件検出する', () => {
    const en = structuredClone(CLEAN_EN) as { a: Record<string, unknown> }
    delete en.a.title
    expect(checkKeySet(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.title'])
  })

  it('AC-4: 他言語にだけあるキーを1件検出する', () => {
    const en = structuredClone(CLEAN_EN) as { a: Record<string, unknown> }
    en.a.extra = 'Extra'
    expect(checkKeySet(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.extra'])
  })

  it('AC-5: プレースホルダ名の不一致を1件検出する', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.count = '{count} items'
    expect(checkPlaceholders(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.count'])
  })

  it("AC-5: リテラル補間 {'@'} はプレースホルダとして数えない", () => {
    const ja = doc('ja', { m: "user{'@'}example.com" })
    expect(checkPlaceholders(ja, doc('en', { m: 'user@example.com' }))).toEqual([])
  })

  it('AC-6: ja に | があるキーで区切りの数が違えば1件検出する', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.plural = 'one | {n} items'
    expect(checkPluralSeparators(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.plural'])
  })

  it('AC-6: ja に | が無いキーへ en が複数形を入れても違反にしない', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.count = '{n} item | {n} items'
    expect(checkPluralSeparators(JA, doc('en', en))).toEqual([])
  })

  it("AC-6: リテラル補間 {'|'} は区切りとして数えない", () => {
    const ja = doc('ja', { m: "A{'|'}B" })
    expect(checkPluralSeparators(ja, doc('en', { m: "A{'|'}B" }))).toEqual([])
  })

  it('AC-7: コンパイルできないメッセージを1件検出する', () => {
    const v = checkCompiles(doc('en', { ok: 'Hello {name}', ng: 'Hello {name' }))
    expect(v.map((x) => x.path)).toEqual(['ng'])
  })

  it('AC-8: es のアクセント欠落綴りを語境界・大小無視で1件検出する', () => {
    const v = checkEsAccents(doc('es', { a: 'Contraseña', b: 'Configuracion', c: 'Información' }))
    expect(v.map((x) => x.path)).toEqual(['b'])
  })

  it('AC-8: 語としての「mas」を検出し、「más」「Thomas」は検出しない', () => {
    const v = checkEsAccents(doc('es', { a: 'Ver más', b: 'Thomas', c: 'Ver mas' }))
    expect(v.map((x) => x.path)).toEqual(['c'])
  })

  it('AC-9a: ja で空文字の値が他言語で非空なら1件検出する', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.empty = 'Something'
    expect(checkEmptyStaysEmpty(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.empty'])
  })

  it('AC-9b: ja で非空の値が他言語で空白だけなら1件検出する', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.title = '  '
    expect(checkNonEmptyStaysNonEmpty(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.title'])
  })

  it('AC-9b: 基準リストに理由付きで載せた空値（語順の都合）は除外され、他の空値化は残る', () => {
    const ja = doc('ja', { suffix: '限', title: 'タイトル' })
    const violations = checkNonEmptyStaysNonEmpty(ja, doc('en', { suffix: '', title: '' }))
    const entries: BaselineEntry[] = [
      { rule: 'AC-9b', lang: 'en', file: 'fixture.json', path: 'suffix', reason: '英語は接尾辞を置かない語順' },
    ]
    const r = applyExemptions(violations, entries)
    expect({ remaining: r.remaining.map((x) => x.path), unused: r.unused }).toEqual({ remaining: ['title'], unused: [] })
  })

  it('AC-9c: 値の型の不一致を1件検出する', () => {
    const en = structuredClone(CLEAN_EN) as { a: Record<string, unknown> }
    en.a.title = { text: 'Title' }
    const v = checkTypesAndLengths(JA, doc('en', en))
    expect(v.map((x) => x.path)).toEqual(['a.title'])
  })

  it('AC-9c: 配列長の不一致を1件検出する', () => {
    const en = structuredClone(CLEAN_EN)
    en.a.list = ['one']
    expect(checkTypesAndLengths(JA, doc('en', en)).map((x) => x.path)).toEqual(['a.list'])
  })

  it('違反の無い fixture ではどの検査も0件', () => {
    const en = doc('en', CLEAN_EN)
    const all = [
      ...checkNoJapaneseScript(en),
      ...checkKeySet(JA, en),
      ...checkPlaceholders(JA, en),
      ...checkPluralSeparators(JA, en),
      ...checkCompiles(en),
      ...checkEmptyStaysEmpty(JA, en),
      ...checkNonEmptyStaysNonEmpty(JA, en),
      ...checkTypesAndLengths(JA, en),
    ]
    expect(all).toEqual([])
  })
})

describe('検出器の自己検査: 対象の列挙と読み込み（AC-10）', () => {
  const langs = [BASE_LANG, ...TARGET_LANGS]
  const configOf = (files: (lang: string) => string[]): string =>
    `export default defineNuxtConfig({ i18n: { locales: [\n${langs
      .map((l) => `{ code: '${l}', name: 'x', files: [${files(l).map((f) => `'${f}'`).join(', ')}] },`)
      .join('\n')}\n], defaultLocale: 'ja' } })`
  let dir: string

  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'locale-residue-'))
    for (const l of langs) {
      mkdirSync(join(dir, l))
      // BOM 付き JSON と、default export を持つ TS を置く
      writeFileSync(join(dir, l, 'a.json'), String.fromCharCode(0xfeff) + JSON.stringify({ k: l }))
      writeFileSync(join(dir, l, 'r.ts'), `export default { r: { k: '${l}' } }\n`)
    }
  })

  afterAll(() => {
    rmSync(dir, { recursive: true, force: true })
  })

  it('登録一覧どおりに BOM 付き JSON と TS の default export を読み込む', async () => {
    const reg = parseRegisteredLocaleFiles(configOf((l) => [`${l}/a.json`, `${l}/r.ts`]))
    const r = await loadRegisteredLocales(dir, reg)
    expect({ errors: r.errors, en: r.byFile.get('r.ts')?.get('en')?.messages }).toEqual({ errors: [], en: { r: { k: 'en' } } })
  })

  it('登録されたファイルが存在しなければエラーにする', async () => {
    const reg = parseRegisteredLocaleFiles(configOf((l) => [`${l}/a.json`, `${l}/missing.json`]))
    const r = await loadRegisteredLocales(dir, reg)
    expect(r.errors.filter((e) => e.includes('missing.json'))).toHaveLength(langs.length)
  })

  it('パースできないファイルがあればエラーにする', async () => {
    writeFileSync(join(dir, 'de', 'broken.json'), '{ "k": ')
    const reg = parseRegisteredLocaleFiles(configOf((l) => [`${l}/a.json`, ...(l === 'de' ? ['de/broken.json'] : [])]))
    const r = await loadRegisteredLocales(dir, reg)
    expect(r.errors.some((e) => e.startsWith('de/broken.json'))).toBe(true)
  })

  it('ある言語にだけ登録が欠けたファイルをエラーにする', async () => {
    const reg = parseRegisteredLocaleFiles(configOf((l) => (l === 'ko' ? [`${l}/a.json`] : [`${l}/a.json`, `${l}/r.ts`])))
    const r = await loadRegisteredLocales(dir, reg)
    expect(r.errors).toEqual(['ko: r.ts が nuxt.config.ts に未登録（ja には登録あり）'])
  })

  it('locales 配列が見つからない設定は例外にする', () => {
    expect(() => parseRegisteredLocaleFiles('export default defineNuxtConfig({})')).toThrow()
  })

  it('files が0件の言語がある設定は例外にする（対象0件の空振りを防ぐ）', () => {
    expect(() => parseRegisteredLocaleFiles(configOf(() => []))).toThrow()
  })
})
