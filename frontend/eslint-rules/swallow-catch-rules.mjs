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

// --- ここから Issue #2770 で追加した「オブジェクトで包んだ空値」の判定 ---

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

const MESSAGE_TRY_CATCH
  = 'catchでエラーを握りつぶして空配列/null/空オブジェクト/undefined/空returnを返さないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、握りつぶす正当な理由をコメントで明記のうえ該当行のみ eslint-disable-next-line で個別に許可すること。'
const MESSAGE_CATCH_EXPR
  = 'Promiseの.catchでエラーを握りつぶして空配列/null/空オブジェクト/undefinedを返さないこと。取得失敗が0件表示や無反応に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_CATCH_BLOCK
  = 'Promiseの.catchでの握りつぶし返却（空配列/null/空オブジェクト/undefined/空return）を禁止。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_WRAPPED
  = 'catchで「中身が空のオブジェクト」（例: { data: [] } / { items: [], total: 0 }）を返して握りつぶさないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'

/** `no-restricted-syntax` に渡すエントリ群（オプション配列の中身）。 */
export const swallowCatchRestrictions = [
  // ルールB: try/catch のブロック本体が単一 return の空値返却
  {
    selector: `CatchClause > BlockStatement[body.length=1] > ReturnStatement:matches(${EMPTY_ARGUMENT})`,
    message: MESSAGE_TRY_CATCH,
  },
  // ルールC: Promise .catch(() => 空fallback)（式本体：() => [] / null / undefined / ({}) / {}）。
  // 取得失敗が0件表示や無反応に偽装される事故を予防する。named handler・本体2文以上・非空値返却は対象外。
  {
    selector: `CallExpression[callee.property.name='catch'] > ArrowFunctionExpression:matches(${EMPTY_BODY})`,
    message: MESSAGE_CATCH_EXPR,
  },
  // ルールD: Promise .catch(() => { return 空fallback }) のブロック本体で単一returnの握りつぶし
  {
    selector: `CallExpression[callee.property.name='catch'] > ArrowFunctionExpression > BlockStatement[body.length=1] > ReturnStatement:matches(${EMPTY_ARGUMENT})`,
    message: MESSAGE_CATCH_BLOCK,
  },
  // ルールE（Issue #2770）: `.catch(() => ({ data: [] }))` — オブジェクトで包んだ空値（式本体）
  {
    selector: `CallExpression[callee.property.name='catch'] > ArrowFunctionExpression > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },
  // ルールF（Issue #2770）: `.catch(() => { return { data: [] } })` — オブジェクトで包んだ空値（ブロック本体）
  {
    selector: `CallExpression[callee.property.name='catch'] > ArrowFunctionExpression > BlockStatement[body.length=1] > ReturnStatement > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },
  // ルールG（Issue #2770）: try/catch で `return { data: [] }` — オブジェクトで包んだ空値
  {
    selector: `CatchClause > BlockStatement[body.length=1] > ReturnStatement > ${SWALLOW_OBJECT}`,
    message: MESSAGE_WRAPPED,
  },
]
