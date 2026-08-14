// エラー握りつぶし禁止ガード（no-restricted-syntax セレクタ群）の正本。
//
// eslint.config.mjs と自己検証テスト（tests/unit/eslint/swallow-catch-guard.spec.ts）の
// 双方がこのファイルを参照する。セレクタを直したらテストが同じセレクタで検証されるため、
// 「設定は直したが検証は古いセレクタのまま」という食い違いが起きない。
//
// 【Issue #2770 の是正】
// 従来のセレクタは「空値そのもの」（`[]` / `{}` / `null` / `undefined`）しか見ておらず、
// `.catch(() => ({ data: [] }))` のように **非空オブジェクトで包んだ空値** を素通りさせていた。
// API クライアントの `.catch()` は `{ data: ... }` 形を返すのが自然なため、
// 本プロジェクトで最も起こりやすい握りつぶしの形がちょうど検出漏れしていた。
//
// 【Issue #2770 差し戻し分の是正】
// さらに「返り値そのものを型アサーションで包む」迂回路が残っていた。
//   .catch(() => ({ data: [] } as ApiResponse))   // アロー本体が TSAsExpression
//   catch { return { data: [] } as ApiResponse }  // return の argument が TSAsExpression
// これは TypeScript ではごく一般的な記法であり、塞がないと同じ偽陰性が再発する。
// 「内側」（プロパティの値が `[] as T[]`）と「外側」（返り値全体の包み）は別々の対処が要る。

// ---------------------------------------------------------------------------
// 型レベルの包み（外側）の透過
// ---------------------------------------------------------------------------

/**
 * 値としては素通しで、型だけを付け替える式ノード。
 * これらは「返り値そのもの」を包んで検出器を迂回できてしまうため、
 * 中身まで辿って判定する必要がある。
 * なお TS-ESTree では括弧はノードにならない（`({ ... })` は追加ノードを生まない）ため、
 * 括弧に伴う包みはこの3種の連なりとして現れる。
 */
const TYPE_WRAPPER = ':matches(TSAsExpression, TSSatisfiesExpression, TSNonNullExpression)'

/**
 * 包みの最大段数。`({ data: [] } as A) as B` のような多重の包みに備える。
 * esquery には「0段以上の繰り返し」を表す構文が無いため、段数を明示的に展開する。
 * 3段を超える包みは現実には現れないうえ、超えたところで型を付け替えているだけなので
 * 検出器の意義を損なわない。
 */
const MAX_WRAPPER_DEPTH = 3

/** `base` から `target` までの間に型の包みが 1〜MAX_WRAPPER_DEPTH 段ある経路を列挙する。 */
function throughTypeWrappers(base, target) {
  const paths = []
  for (let depth = 1; depth <= MAX_WRAPPER_DEPTH; depth++) {
    const chain = Array.from({ length: depth }, () => `> ${TYPE_WRAPPER}`).join(' ')
    paths.push(`${base} ${chain} > ${target}`)
  }
  return paths
}

// ---------------------------------------------------------------------------
// 「空値そのもの」の判定
// ---------------------------------------------------------------------------

/** 「空値そのもの」の返却（従来から検出していた形）。ReturnStatement の argument 位置に使う。 */
const EMPTY_ARGUMENT = [
  "[argument.type='ArrayExpression'][argument.elements.length=0]",
  "[argument.type='ObjectExpression'][argument.properties.length=0]",
  "[argument.type='Literal'][argument.raw='null']",
  "[argument.type='Identifier'][argument.name='undefined']",
  ':not([argument])',
].join(', ')

/** 「空値そのもの」の返却。アロー関数の式本体（body 位置）に使う。 */
const EMPTY_BODY = [
  "[body.type='ArrayExpression'][body.elements.length=0]",
  "[body.type='ObjectExpression'][body.properties.length=0]",
  "[body.type='Literal'][body.raw='null']",
  "[body.type='Identifier'][body.name='undefined']",
  "[body.type='BlockStatement'][body.body.length=0]",
].join(', ')

/**
 * 「空値そのもの」をノード単体として表したもの。
 * 型の包みを1段以上挟んだ先を見るときは、親の属性では辿れないためこちらを使う。
 */
const EMPTY_NODE = ':matches('
  + [
    'ArrayExpression[elements.length=0]',
    'ObjectExpression[properties.length=0]',
    "Literal[raw='null']",
    "Identifier[name='undefined']",
  ].join(', ')
  + ')'

// ---------------------------------------------------------------------------
// 「オブジェクトで包んだ空値」の判定（内側）
// ---------------------------------------------------------------------------

/**
 * 「中身が空」と見なすプロパティ値。
 * TSAsExpression を併記しているのは `{ data: [] as GanttTodo[] }` のような
 * 型アサーション付きの空配列（Issue #2637 の実物がこの形だった）を取りこぼさないため。
 */
const EMPTY_PROPERTY = [
  "[value.type='ArrayExpression'][value.elements.length=0]",
  "[value.type='ObjectExpression'][value.properties.length=0]",
  "[value.type='Literal'][value.raw='null']",
  "[value.type='Identifier'][value.name='undefined']",
  "[value.type='TSAsExpression'][value.expression.type='ArrayExpression'][value.expression.elements.length=0]",
  "[value.type='TSAsExpression'][value.expression.type='ObjectExpression'][value.expression.properties.length=0]",
].join(', ')

/**
 * 「空の付き添い」に過ぎない値。`{ items: [], total: 0 }` の `total: 0` のような
 * 件数・フラグの類を指す。これ単体では握りつぶしと断じないが（後述の
 * 「空値を1つ以上含むこと」を必須条件にしている）、空配列に随伴する場合は
 * 握りつぶしの一部として扱う。
 */
const NEUTRAL_PROPERTY = [
  "[value.type='Literal'][value.raw='0']",
  "[value.type='Literal'][value.raw='false']",
  "[value.type='Literal'][value.raw=\"''\"]",
  "[value.type='Literal'][value.raw='\"\"']",
].join(', ')

/**
 * 握りつぶしオブジェクトのセレクタ。過剰検出を避けるため、以下をすべて満たす場合のみ一致する:
 *
 * 1. プロパティが1つ以上ある（空オブジェクト `{}` は従来ルールの担当）
 * 2. スプレッド（`{ ...base, data: [] }`）を含まない
 *    — 実データを土台にしているため握りつぶしと断定できない
 * 3. すべてのプロパティが「空値」か「空の付き添い」である
 *    — `{ data: [1] }` や `{ data: [], error: e }` のように実のある値が1つでもあれば対象外
 * 4. 「空値」プロパティを少なくとも1つ含む
 *    — `{ ok: false }` のような単なるフラグ返却を巻き込まない
 */
const SWALLOW_OBJECT = [
  'ObjectExpression',
  ':not([properties.length=0])',
  ':not(:has(> SpreadElement))',
  `:not(:has(> Property:not(:matches(${EMPTY_PROPERTY}, ${NEUTRAL_PROPERTY}))))`,
  `:has(> Property:matches(${EMPTY_PROPERTY}))`,
].join('')

// ---------------------------------------------------------------------------
// ルール定義
// ---------------------------------------------------------------------------

const CATCH_ARROW = "CallExpression[callee.property.name='catch'] > ArrowFunctionExpression"
const CATCH_ARROW_RETURN = `${CATCH_ARROW} > BlockStatement[body.length=1] > ReturnStatement`
const TRY_CATCH_RETURN = 'CatchClause > BlockStatement[body.length=1] > ReturnStatement'

const MESSAGE_TRY_CATCH
  = 'catchでエラーを握りつぶして空配列/null/空オブジェクト/undefined/空returnを返さないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、握りつぶす正当な理由をコメントで明記のうえ該当行のみ eslint-disable-next-line で個別に許可すること。'
const MESSAGE_CATCH_EXPR
  = 'Promiseの.catchでエラーを握りつぶして空配列/null/空オブジェクト/undefinedを返さないこと。取得失敗が0件表示や無反応に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_CATCH_BLOCK
  = 'Promiseの.catchでの握りつぶし返却（空配列/null/空オブジェクト/undefined/空return）を禁止。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_WRAPPED
  = 'catchで「中身が空のオブジェクト」（例: { data: [] } / { items: [], total: 0 }）を返して握りつぶさないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_TYPE_WRAPPED
  = 'catchでの握りつぶし返却を型アサーション（as / satisfies / !）で包んでも握りつぶしであることは変わらない。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'

/** `no-restricted-syntax` に渡すエントリ群（オプション配列の中身）。 */
export const swallowCatchRestrictions = [
  // --- 空値そのものの返却（従来のルールB/C/D） ---

  // ルールB: try/catch のブロック本体が単一 return の空値返却
  {
    selector: `${TRY_CATCH_RETURN}:matches(${EMPTY_ARGUMENT})`,
    message: MESSAGE_TRY_CATCH,
  },
  // ルールC: Promise .catch(() => 空fallback)（式本体：() => [] / null / undefined / ({}) / {}）。
  // 取得失敗が0件表示や無反応に偽装される事故を予防する。named handler・本体2文以上・非空値返却は対象外。
  {
    selector: `${CATCH_ARROW}:matches(${EMPTY_BODY})`,
    message: MESSAGE_CATCH_EXPR,
  },
  // ルールD: Promise .catch(() => { return 空fallback }) のブロック本体で単一returnの握りつぶし
  {
    selector: `${CATCH_ARROW_RETURN}:matches(${EMPTY_ARGUMENT})`,
    message: MESSAGE_CATCH_BLOCK,
  },

  // --- オブジェクトで包んだ空値（Issue #2770 のルールE/F/G） ---

  // ルールE: `.catch(() => ({ data: [] }))` — 式本体
  {
    selector: `${CATCH_ARROW} > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },
  // ルールF: `.catch(() => { return { data: [] } })` — ブロック本体
  {
    selector: `${CATCH_ARROW_RETURN} > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },
  // ルールG: try/catch で `return { data: [] }`
  {
    selector: `${TRY_CATCH_RETURN} > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },

  // --- 型アサーションで外側を包んだ迂回（Issue #2770 差し戻し分のルールH/I） ---
  // 上の全経路について、返り値そのものが as / satisfies / ! で包まれている場合を辿る。
  // 空値そのもの（[] as T[] / null! など）と、オブジェクトで包んだ空値
  // （{ data: [] } as ApiResponse）の双方を対象にする。

  // ルールH: 型の包みの先が「空値そのもの」
  {
    selector: [
      ...throughTypeWrappers(CATCH_ARROW, EMPTY_NODE),
      ...throughTypeWrappers(CATCH_ARROW_RETURN, EMPTY_NODE),
      ...throughTypeWrappers(TRY_CATCH_RETURN, EMPTY_NODE),
    ].join(', '),
    message: MESSAGE_TYPE_WRAPPED,
  },
  // ルールI: 型の包みの先が「中身が空のオブジェクト」
  {
    selector: [
      ...throughTypeWrappers(CATCH_ARROW, SWALLOW_OBJECT),
      ...throughTypeWrappers(CATCH_ARROW_RETURN, SWALLOW_OBJECT),
      ...throughTypeWrappers(TRY_CATCH_RETURN, SWALLOW_OBJECT),
    ].join(', '),
    message: MESSAGE_TYPE_WRAPPED,
  },
]
