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

/**
 * 検体を lint して違反件数を返す。
 *
 * 検体は必ず `.ts` として食わせる。山括弧形式の型アサーション（`<Foo>[]`）は
 * `.tsx` では JSX と曖昧になりパースエラーになるため、拡張子を誤ると
 * 「違反0件」＝健全と誤読してしまう。
 */
function countViolations(code: string, filename = 'fixture.ts'): number {
  const messages = linter.verify(code, [{
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
        ecmaFeatures: { jsx: filename.endsWith('.tsx') },
      },
    },
    rules: { 'no-restricted-syntax': ['error', ...swallowCatchRestrictions] },
  }], filename)
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

      // --- 4巡目: 山括弧形式の型アサーション（TSTypeAssertion） ---
      // `.vue` / `.tsx` では JSX と曖昧になり成立しないが、`.ts` では有効な構文。
      '山括弧で返り値を包む': 'foo().catch(() => (<ApiResponse>{ data: [] }))',
      '山括弧でプロパティ値を包む': 'foo().catch(() => ({ data: <Foo[]>[] }))',
      '山括弧で null を包む': 'foo().catch(() => ({ data: <Foo | null>null }))',
      '山括弧と as の混在': 'foo().catch(() => (<ApiResponse>{ data: [] as Foo[] }))',
      '山括弧で空配列そのものを包む': 'foo().catch(() => (<Foo[]>[]))',

      // --- 5巡目: 透過の上限を超える段数は中身を問わず違反（fail-closed） ---
      '4段の包み（オブジェクト）': 'foo().catch(() => (((({ data: [] } as A) as B) as C) as D))',
      '5段の包み（オブジェクト）': 'foo().catch(() => (((((({ data: [] } as A) as B) as C) as D) as E)))',
      '4段の包み（空配列）': 'foo().catch(() => (((([] as A) as B) as C) as D))',
      '4段の包み（as と山括弧の混在）': 'foo().catch(() => (<D>((<B>({ data: [] } as A)) as C)))',
      '4段の包み（ブロック本体）': 'foo().catch(() => { return (((({ data: [] } as A) as B) as C) as D) })',
      '4段の包み（try/catch）': 'function f() { try { g() } catch { return (((({ data: [] } as A) as B) as C) as D) } }',
      // 中身が握りつぶしでなくても、上限超過そのものを違反とする（fail-closed）
      '4段の包み（中身は実のある値）': 'foo().catch(() => (((({ data: [1] } as A) as B) as C) as D))',

      // --- 6巡目: 入れ子の空レスポンス ---
      '入れ子 { data: { items: [], total: 0 } }': 'foo().catch(() => ({ data: { items: [], total: 0 } }))',
      '入れ子 { data: { data: [] } }': 'foo().catch(() => ({ data: { data: [] } }))',
      '入れ子 { data: { items: [], nextCursor: null } }': 'foo().catch(() => ({ data: { items: [], nextCursor: null } }))',
      '入れ子の値に型ラッパー': 'foo().catch(() => ({ data: { items: [] } as Page }))',
      '入れ子＋外側に型ラッパー': 'foo().catch(() => ({ data: { items: [] } } as ApiResponse))',
      '入れ子＋山括弧': 'foo().catch(() => ({ data: <Page>{ items: [] } }))',
      '2段の入れ子': 'foo().catch(() => ({ data: { result: { items: [] } } }))',
      // 子孫セレクタで判定するため深さに上限が無いことの固定
      '5段の入れ子': 'foo().catch(() => ({ a: { b: { c: { d: { e: [] } } } } }))',
      '入れ子（ブロック本体）': 'foo().catch(() => { return { data: { items: [], total: 0 } } })',
      '入れ子（try/catch）': 'function f() { try { g() } catch { return { data: { items: [], total: 0 } } } }',
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

      // --- 4巡目: 山括弧形式でも過剰検出しないこと ---
      '山括弧だが非空配列': 'foo().catch(() => (<ApiResponse>{ data: [1] }))',
      '山括弧だが変数参照': 'foo().catch(() => ({ data: <Foo[]>items }))',

      // --- 5巡目: 上限内なら従来どおり中身の判定が効き続けること ---
      // 上限超過ルールが「3段までの正当な包み」を巻き込んでいないことの固定
      '2段の包みで実のある値': 'foo().catch(() => (({ data: [1] } as A) as B))',
      '3段の包みで実のある値': 'foo().catch(() => ((({ data: [1] } as A) as B) as C))',
      '3段の包みで変数参照': 'foo().catch(() => (((items as A) as B) as C))',
      '3段の包みでエラー保持': 'foo().catch(e => ((({ data: [], error: e } as A) as B) as C))',

      // --- 6巡目: 入れ子でも過剰検出しないこと ---
      '入れ子に非空配列': 'foo().catch(() => ({ data: { items: [1], total: 1 } }))',
      '入れ子にエラー保持': 'foo().catch(e => ({ data: { items: [], error: e } }))',
      '入れ子に実体参照': 'foo().catch(() => ({ data: { user: someUser } }))',
      '入れ子に null と非空配列の混在': 'foo().catch(() => ({ data: { user: null, items: [1] } }))',
      '2段の入れ子で実のある値': 'foo().catch(() => ({ data: { result: { items: [1] } } }))',
      '入れ子にスプレッド': 'foo().catch(() => ({ data: { ...base, items: [] } }))',
      '入れ子に関数を持つ': 'foo().catch(() => ({ data: { items: [] }, retry: () => load() }))',
    }

    for (const [name, code] of Object.entries(allowed)) {
      it(`検出しない: ${name}`, () => {
        expect(countViolations(code)).toBe(0)
      })
    }
  })

  // このフィクスチャ自身が「0件＝健全」と誤読しない仕組みを持つことの検証。
  // 検体がパースできていないのに違反0件で通ってしまうと、ガードの検証全体が
  // 静かに無意味になる（偽の緑）。
  describe('検体のパース失敗を握りつぶさないこと', () => {
    it('構文エラーの検体は 0件 ではなく例外になる', () => {
      expect(() => countViolations('foo().catch(() => ({ data: [ }))')).toThrow(/検体のパースに失敗/)
    })

    it('山括弧形式を .tsx として食わせるとパースエラーになり、0件と誤読しない', () => {
      const code = 'foo().catch(() => (<ApiResponse>{ data: [] }))'
      // .ts なら握りつぶしとして検出される
      expect(countViolations(code, 'fixture.ts')).toBeGreaterThan(0)
      // .tsx では JSX と曖昧になり成立しない。黙って 0 件にせず例外にする
      expect(() => countViolations(code, 'fixture.tsx')).toThrow(/検体のパースに失敗/)
    })
  })
})
