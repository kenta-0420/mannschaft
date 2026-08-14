import { Linter } from 'eslint'
import tsParser from '@typescript-eslint/parser'
import { describe, expect, it } from 'vitest'
// ESLint 設定が読むセレクタの正本をそのまま検証対象にする（設定と検証の食い違いを防ぐ）
import { swallowCatchRestrictions } from '../../../eslint-rules/swallow-catch-rules.mjs'

/**
 * エラー握りつぶし禁止ガードの自己検証フィクスチャ（Issue #2770）。
 *
 * 検出器は自分の偽陰性を最初に晒すべきである。実際 #2770 では
 * `.catch(() => ({ data: [] }))` を素通りさせていたことが、
 * 人間の目視でしか発見できなかった。この検体一式によってその穴を固定する。
 *
 * 「検出されるべき検体」と「検出されてはならない検体」の両方を置くことで、
 * 穴の再発と、過剰検出（正当な .catch まで落として eslint-disable 乱発を招く）の
 * 双方を防ぐ。
 */

const linter = new Linter()

/** 検体を lint して違反件数を返す。 */
function countViolations(code: string): number {
  const messages = linter.verify(code, [{
    files: ['**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: { 'no-restricted-syntax': ['error', ...swallowCatchRestrictions] },
  }], 'fixture.ts')
  // パースエラーが検体側の書き間違いで紛れ込むと「0件＝健全」と誤読するため明示的に弾く
  const fatal = messages.find(m => m.fatal)
  if (fatal) throw new Error(`検体のパースに失敗: ${fatal.message}`)
  return messages.length
}

describe('エラー握りつぶし禁止ガード', () => {
  describe('検出されるべき検体（握りつぶし）', () => {
    const detected: Record<string, string> = {
      // --- 従来から検出できていた形（回帰防止） ---
      '.catch(() => [])': 'foo().catch(() => [])',
      '.catch(() => null)': 'foo().catch(() => null)',
      '.catch(() => undefined)': 'foo().catch(() => undefined)',
      '.catch(() => ({}))': 'foo().catch(() => ({}))',
      '.catch(() => {})': 'foo().catch(() => {})',
      '.catch(() => { return [] })': 'foo().catch(() => { return [] })',
      'try/catch で return []': 'function f() { try { g() } catch { return [] } }',
      'try/catch で 引数なし return': 'function f() { try { g() } catch { return } }',

      // --- Issue #2770 で初めて検出できるようになった形 ---
      '.catch(() => ({ data: [] }))': 'foo().catch(() => ({ data: [] }))',
      '.catch(() => ({ items: [] }))': 'foo().catch(() => ({ items: [] }))',
      '.catch(() => ({ data: null }))': 'foo().catch(() => ({ data: null }))',
      '.catch(() => ({ data: {} }))': 'foo().catch(() => ({ data: {} }))',
      '.catch(() => ({ data: undefined }))': 'foo().catch(() => ({ data: undefined }))',
      // #2637 の実物と同じ形（型アサーション付きの空配列）
      '.catch(() => ({ data: [] as T[] }))': 'foo().catch(() => ({ data: [] as string[] }))',
      // 空配列に件数0が随伴するページング形
      '.catch(() => ({ items: [], total: 0 }))': 'foo().catch(() => ({ items: [], total: 0 }))',
      '複数プロパティがすべて空': 'foo().catch(() => ({ data: [], meta: null }))',
      'ブロック本体で包んだ空値': 'foo().catch(() => { return { data: [] } })',
      'try/catch で return { data: [] }': 'function f() { try { g() } catch { return { data: [] } } }',

      // --- 差し戻し分: 返り値そのものを型アサーションで包む迂回 ---
      '外側 as で包んだ空オブジェクト返却': 'foo().catch(() => ({ data: [] } as ApiResponse))',
      '外側 as（ブロック本体）': 'foo().catch(() => { return { data: [] } as ApiResponse })',
      '外側 as（try/catch）': 'function f() { try { g() } catch { return { data: [] } as ApiResponse } }',
      '多重の包み': 'foo().catch(() => (({ data: [] } as A) as B))',
      'satisfies で包む': 'foo().catch(() => ({ data: [] } satisfies ApiResponse))',
      '非null表明で包む': 'foo().catch(() => ({ data: [] } as ApiResponse)!)',
      '外側 as で包んだ空配列そのもの': 'foo().catch(() => ([] as string[]))',
      '外側 as で包んだ空配列（try/catch）': 'function f() { try { g() } catch { return [] as string[] } }',
      '外側 as で包んだ null': 'foo().catch(() => (null as unknown as ApiResponse))',

      // --- 再差し戻し分: プロパティ値そのものを型アサーションで包む迂回 ---
      'プロパティ値 null as': 'foo().catch(() => ({ data: null as Foo | null }))',
      'プロパティ値 satisfies': 'foo().catch(() => ({ data: [] satisfies Foo[] }))',
      'プロパティ値 undefined as': 'foo().catch(() => ({ data: undefined as Foo | undefined }))',
      'プロパティ値 空オブジェクト as': 'foo().catch(() => ({ data: {} as Foo }))',
      'プロパティ値 非null表明': 'foo().catch(() => ({ data: ([] as Foo[])! }))',
      'プロパティ値 多重の包み': 'foo().catch(() => ({ data: ([] as Foo[]) as Bar[] }))',
      '外側とプロパティ値の両方に包み': 'foo().catch(() => ({ data: [] as Foo[] } as ApiResponse))',
      '付き添いも含めて全部包む': 'foo().catch(() => ({ items: [] as Foo[], total: 0 as number }))',
    }

    for (const [name, code] of Object.entries(detected)) {
      it(`検出する: ${name}`, () => {
        expect(countViolations(code)).toBeGreaterThan(0)
      })
    }
  })

  describe('検出されてはならない検体（正当な catch）', () => {
    const allowed: Record<string, string> = {
      // 実のある値を返している
      '.catch(() => ({ data: [1] }))': 'foo().catch(() => ({ data: [1] }))',
      '.catch(() => ({ data: [], error: e }))': 'foo().catch(e => ({ data: [], error: e }))',
      '.catch(() => ({ data: [], message: "失敗" }))': 'foo().catch(() => ({ data: [], message: "失敗" }))',
      // 実データを土台にしたスプレッドは握りつぶしと断定できない
      'スプレッドを含む': 'foo().catch(() => ({ ...base, data: [] }))',
      // フラグだけの返却（空値を含まない）
      '.catch(() => ({ ok: false }))': 'foo().catch(() => ({ ok: false }))',
      // ログ・通知・再throw で表面化させている
      '再throw している': 'foo().catch((e) => { throw e })',
      'ログを出してから空配列': 'foo().catch((e) => { report(e); return [] })',
      'try/catch でログしてから空配列': 'function f() { try { g() } catch (e) { report(e); return [] } }',
      // catch 以外のメソッドは対象外
      '.then(() => ({ data: [] }))': 'foo().then(() => ({ data: [] }))',
      // 通常のオブジェクト初期化を巻き込まない
      '通常の変数初期化': 'const state = { data: [] }',
      '関数の通常 return': 'function f() { return { data: [] } }',

      // --- 差し戻し分: 型アサーションで包んでも過剰検出しないこと ---
      '外側 as だが実のある値': 'foo().catch(() => ({ data: [1] } as ApiResponse))',
      '外側 as だがエラーを保持': 'foo().catch(e => ({ data: [], error: e } as ApiResponse))',
      '外側 as だがスプレッド': 'foo().catch(() => ({ ...base, data: [] } as ApiResponse))',
      '外側 as だが空値を含まない': 'foo().catch(() => ({ ok: false } as ApiResponse))',
      '通常の変数初期化を as で包む': 'const state = { data: [] } as ApiResponse',
      '.then を as で包む': 'foo().then(() => ({ data: [] } as ApiResponse))',

      // --- 再差し戻し分: プロパティ値を包んでも過剰検出しないこと ---
      // 「実のある値だから対象外」の除外判定も透過するため、包みの有無で結論がぶれない
      'プロパティ値 as だが非空配列': 'foo().catch(() => ({ data: [1] as Foo[] }))',
      'プロパティ値 as だが変数参照': 'foo().catch(() => ({ data: items as Foo[] }))',
      'プロパティ値 as ＋ エラー保持': 'foo().catch(e => ({ data: [] as Foo[], error: e }))',
      'プロパティ値 as だが空値なし': 'foo().catch(() => ({ ok: false as boolean }))',
      'プロパティ値 as で包んだ非空オブジェクト': 'foo().catch(() => ({ data: { id: 1 } as Foo }))',
    }

    for (const [name, code] of Object.entries(allowed)) {
      it(`検出しない: ${name}`, () => {
        expect(countViolations(code)).toBe(0)
      })
    }
  })
})
