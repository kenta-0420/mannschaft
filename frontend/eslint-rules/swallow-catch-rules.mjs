// エラー握りつぶし禁止ガード（no-restricted-syntax セレクタ群）の正本。
//
// eslint.config.mjs と自己検証テスト（tests/unit/eslint/swallow-catch-guard.spec.ts）の
// 双方がこのファイルを参照する。セレクタを直したらテストが同じセレクタで検証されるため、
// 「設定は直したが検証は古いセレクタのまま」という食い違いが起きない。
//
// ============================================================================
// 設計方針 — なぜ「生成」しているのか
// ============================================================================
//
// このガードは Issue #2770 で3度続けて同じ形の偽陰性を出した:
//   1回目: `.catch(() => ({ data: [] }))`            オブジェクトで包んだ空値
//   2回目: `.catch(() => ({ data: [] } as Foo))`     返り値そのものを型で包む
//   3回目: `.catch(() => ({ data: null as Foo }))`   プロパティ値を型で包む
//
// 原因は毎回同じで、「型ラッパーを剥がす」「空値である」という判定が
// 判定箇所ごとに手書きで散らばっていたことにある。手書きでコピーした瞬間、
// コピーし損ねた箇所が次の穴になる。
//
// よってこのファイルは、次の2つだけを概念として定義し、
// それ以外はすべてそこから **生成** する:
//
//   (A) TYPE_WRAPPER_TYPES / MAX_WRAPPER_DEPTH … 型ラッパーの透過（1箇所）
//   (B) EMPTY_KINDS / NEUTRAL_KINDS           … 値の空判定（1箇所）
//
// 返り値そのもの・プロパティ値・「空の付き添い」・「実のある値だから対象外」の
// 除外判定は、すべて (A)(B) の組み合わせから作られる。
// もう一段深い記法が来ても、直すのは (A) か (B) の1箇所だけで全所に効く。

// ============================================================================
// (A) 型ラッパーの透過 — 定義は1箇所
// ============================================================================

/**
 * 値としては素通しで、型だけを付け替える式ノード。
 * これらは判定対象の値を包んで検出器を迂回できてしまうため、中身まで辿る必要がある。
 *
 * いずれも中身を `expression` プロパティに持つため、剥がす操作は
 * 「`.expression` を1段辿る」で統一できる。新しい同種のノードが増えたら
 * **ここに1語足すだけでよい**（`expression` に中身を持つことが条件）。
 * 返り値位置・プロパティ値位置・付き添い判定・除外判定のすべてが
 * この配列から生成されるため、追加漏れの箇所が生じない。
 *
 * `TSTypeAssertion` は山括弧形式（`<Foo>value`）。`.ts` では有効な構文で、
 * `as` 形式と同じ意味を持つため同様に透過する必要がある
 * （`.vue` の SFC や `.tsx` では JSX と曖昧になるため成立しないが、
 * `.ts` ファイルを通じて紛れ込みうる）。
 *
 * なお TS-ESTree では括弧はノードにならない（`({ ... })` は追加ノードを生まない）ため、
 * 括弧に伴う包みもこれらの連なりとして現れる。
 */
const TYPE_WRAPPER_TYPES = [
  'TSAsExpression',
  'TSSatisfiesExpression',
  'TSNonNullExpression',
  'TSTypeAssertion',
]

/**
 * 透過する包みの最大段数。`({ data: [] } as A) as B` のような多重の包みに備える。
 * esquery には「0段以上の繰り返し」を表す構文が無いため、段数を明示的に展開する
 * （展開は下の生成関数が行う。手書きでコピーしないこと）。
 */
const MAX_WRAPPER_DEPTH = 3

/** ノード単体としての型ラッパー。子セレクタの連鎖で辿るとき（:has を使う判定）に用いる。 */
const TYPE_WRAPPER_NODE = `:matches(${TYPE_WRAPPER_TYPES.join(', ')})`

/** `path` から `depth` 段だけ包みを剥がした属性パスを返す。例: ('value', 2) → 'value.expression.expression' */
function unwrapPath(path, depth) {
  return path + '.expression'.repeat(depth)
}

/**
 * `path` の先に `depth` 段の包みが実際に存在することを要求する属性述語を返す。
 * 各段について「TYPE_WRAPPER_TYPES のいずれかである」ことを課す。
 * すべて同一ノード上の属性チェックなので、単純連結で AND になる（組合せ爆発しない）。
 */
function wrapperGuards(path, depth) {
  return Array.from({ length: depth }, (_, level) => {
    const at = unwrapPath(path, level)
    return `:matches(${TYPE_WRAPPER_TYPES.map(type => `[${at}.type='${type}']`).join(', ')})`
  }).join('')
}

/**
 * `base` と `target` の間に型の包みが 0〜MAX_WRAPPER_DEPTH 段ある経路をすべて列挙する。
 * `target` が（:has を含むなどの理由で）属性パスでは表せず、ノードとして書く必要がある場合に使う。
 */
function throughTypeWrappers(base, target) {
  const paths = []
  for (let depth = 0; depth <= MAX_WRAPPER_DEPTH; depth++) {
    const chain = Array.from({ length: depth }, () => `> ${TYPE_WRAPPER_NODE}`).join(' ')
    paths.push(depth === 0 ? `${base} > ${target}` : `${base} ${chain} > ${target}`)
  }
  return paths
}

// ============================================================================
// (B) 値の空判定 — 定義は1箇所
// ============================================================================

/**
 * 「空値そのもの」。属性パスを受け取って述語を返す関数として持つことで、
 * 返り値位置（body / argument）でもプロパティ値位置（value）でも同じ定義を使い回せる。
 */
const EMPTY_KINDS = [
  at => `[${at}.type='ArrayExpression'][${at}.elements.length=0]`,
  at => `[${at}.type='ObjectExpression'][${at}.properties.length=0]`,
  at => `[${at}.type='Literal'][${at}.raw='null']`,
  at => `[${at}.type='Identifier'][${at}.name='undefined']`,
]

/**
 * 「空の付き添い」に過ぎない値。`{ items: [], total: 0 }` の `total: 0` のような
 * 件数・フラグの類を指す。これ単体では握りつぶしと断じない
 * （「空値を1つ以上含むこと」を別途必須にしている）。
 */
const NEUTRAL_KINDS = [
  at => `[${at}.type='Literal'][${at}.raw='0']`,
  at => `[${at}.type='Literal'][${at}.raw='false']`,
  at => `[${at}.type='Literal'][${at}.raw="''"]`,
  at => `[${at}.type='Literal'][${at}.raw='""']`,
]

// ============================================================================
// (A)×(B) の合成 — 以降はすべてここから生成される
// ============================================================================

/**
 * 「`path` の先の値が `kinds` のいずれかである（型の包みは何段でも透過する）」を表す
 * 述語の一覧を返す。呼び出し側はこれをノード名の後ろに連結して使う。
 */
function valueVariants(path, kinds) {
  const variants = []
  for (let depth = 0; depth <= MAX_WRAPPER_DEPTH; depth++) {
    const guards = wrapperGuards(path, depth)
    const at = unwrapPath(path, depth)
    for (const kind of kinds) variants.push(`${guards}${kind(at)}`)
  }
  return variants
}

/** ノード名に述語の一覧を連結し、セレクタリスト（カンマ区切り）にする。 */
function selectorList(node, variants) {
  return variants.map(variant => `${node}${variant}`).join(', ')
}

// --- プロパティ値の判定（オブジェクトの中身を見るとき用） ---

/** 値が空であるプロパティ。`{ data: null as Foo | null }` のような包みも透過する。 */
const EMPTY_PROPERTY = selectorList('Property', valueVariants('value', EMPTY_KINDS))

/** 値が「空の付き添い」であるプロパティ。 */
const NEUTRAL_PROPERTY = selectorList('Property', valueVariants('value', NEUTRAL_KINDS))

/**
 * 握りつぶしオブジェクトのセレクタ。過剰検出を避けるため、以下をすべて満たす場合のみ一致する:
 *
 * 1. プロパティが1つ以上ある（空オブジェクト `{}` は空値そのものとして別途扱う）
 * 2. スプレッド（`{ ...base, data: [] }`）を含まない
 *    — 実データを土台にしているため握りつぶしと断定できない
 * 3. すべてのプロパティが「空値」か「空の付き添い」である
 *    — `{ data: [1] }` や `{ data: [1] as Foo[] }`、`{ data: [], error: e }` のように
 *      実のある値が1つでもあれば対象外。この除外判定も同じ EMPTY/NEUTRAL 定義を
 *      透過込みで使うため、包みの有無で判定がぶれない
 * 4. 「空値」プロパティを少なくとも1つ含む
 *    — `{ ok: false }` のような単なるフラグ返却を巻き込まない
 */
const SWALLOW_OBJECT = [
  'ObjectExpression',
  ':not([properties.length=0])',
  ':not(:has(> SpreadElement))',
  `:not(:has(> Property:not(:matches(${EMPTY_PROPERTY}, ${NEUTRAL_PROPERTY}))))`,
  `:has(> :matches(${EMPTY_PROPERTY}))`,
].join('')

// ============================================================================
// ルール定義
// ============================================================================

const CATCH_ARROW = "CallExpression[callee.property.name='catch'] > ArrowFunctionExpression"
const CATCH_ARROW_RETURN = `${CATCH_ARROW} > BlockStatement[body.length=1] > ReturnStatement`
const TRY_CATCH_RETURN = 'CatchClause > BlockStatement[body.length=1] > ReturnStatement'

/** 返り値そのものが空値である経路（型の包みは透過）。`base` は ReturnStatement を指すセレクタ。 */
function emptyReturn(base) {
  return [
    selectorList(base, valueVariants('argument', EMPTY_KINDS)),
    // `return` のみ（argument なし）
    `${base}:not([argument])`,
  ].join(', ')
}

/** アロー関数の式本体が空値である経路（型の包みは透過）。 */
function emptyArrowBody(base) {
  return [
    selectorList(base, valueVariants('body', EMPTY_KINDS)),
    // `() => {}`（空のブロック本体）
    `${base}[body.type='BlockStatement'][body.body.length=0]`,
  ].join(', ')
}

const MESSAGE_TRY_CATCH
  = 'catchでエラーを握りつぶして空配列/null/空オブジェクト/undefined/空returnを返さないこと（型アサーションで包んでも同じ）。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、握りつぶす正当な理由をコメントで明記のうえ該当行のみ eslint-disable-next-line で個別に許可すること。'
const MESSAGE_CATCH_EXPR
  = 'Promiseの.catchでエラーを握りつぶして空配列/null/空オブジェクト/undefinedを返さないこと（型アサーションで包んでも同じ）。取得失敗が0件表示や無反応に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_CATCH_BLOCK
  = 'Promiseの.catchでの握りつぶし返却（空配列/null/空オブジェクト/undefined/空return）を禁止（型アサーションで包んでも同じ）。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'
const MESSAGE_WRAPPED
  = 'catchで「中身が空のオブジェクト」（例: { data: [] } / { items: [], total: 0 } / { data: null as Foo }）を返して握りつぶさないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。'

/** `no-restricted-syntax` に渡すエントリ群（オプション配列の中身）。 */
export const swallowCatchRestrictions = [
  // --- 空値そのものの返却 ---

  // ルールB: try/catch のブロック本体が単一 return の空値返却
  {
    selector: emptyReturn(TRY_CATCH_RETURN),
    message: MESSAGE_TRY_CATCH,
  },
  // ルールC: Promise .catch(() => 空fallback)（式本体）。
  // named handler・本体2文以上・非空値返却は対象外。
  {
    selector: emptyArrowBody(CATCH_ARROW),
    message: MESSAGE_CATCH_EXPR,
  },
  // ルールD: Promise .catch(() => { return 空fallback }) のブロック本体で単一returnの握りつぶし
  {
    selector: emptyReturn(CATCH_ARROW_RETURN),
    message: MESSAGE_CATCH_BLOCK,
  },

  // --- 中身が空のオブジェクトの返却 ---
  // 返り値そのものの包みは throughTypeWrappers が、
  // プロパティ値の包みは SWALLOW_OBJECT 内の EMPTY/NEUTRAL_PROPERTY が透過する。

  // ルールE: `.catch(() => ({ data: [] }))` — 式本体
  {
    selector: throughTypeWrappers(CATCH_ARROW, SWALLOW_OBJECT).join(', '),
    message: MESSAGE_WRAPPED,
  },
  // ルールF: `.catch(() => { return { data: [] } })` — ブロック本体
  {
    selector: throughTypeWrappers(CATCH_ARROW_RETURN, SWALLOW_OBJECT).join(', '),
    message: MESSAGE_WRAPPED,
  },
  // ルールG: try/catch で `return { data: [] }`
  {
    selector: throughTypeWrappers(TRY_CATCH_RETURN, SWALLOW_OBJECT).join(', '),
    message: MESSAGE_WRAPPED,
  },
]
