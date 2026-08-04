import withNuxt from './.nuxt/eslint.config.mjs'

export default withNuxt({
  // @nuxt/eslint-config は既定で `**/public` をディレクトリ名一致で無視する
  // （Nuxtの静的アセット用ルート public/ を想定した設定）。
  // しかしこのパターンはネスト先の同名ディレクトリにも一致してしまい、
  // app/components/public/ 等のソースコードが黙って lint 対象外になっていた。
  // ルート直下の public/（静的アセット）は無視したまま、ソース配下の
  // 同名ディレクトリだけを対象に戻す。
  ignores: ['!app/**/public/**', '!tests/**/public/**'],
}, {
  rules: {
    '@typescript-eslint/no-explicit-any': 'error',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    'vue/no-v-html': 'warn',
    'vue/component-name-in-template-casing': ['error', 'PascalCase'],
    // ルールA: 空catch禁止（エラーを握りつぶす空のcatchブロックを禁止）
    'no-empty': ['error', { allowEmptyCatch: false }],
    // ルールB: catchでサイレントなfallback値を返すだけの握りつぶしを禁止
    // 取得失敗が空配列/null/空オブジェクト/undefined/空returnで0件表示に偽装される事故を予防する。
    // catch内でログ・通知・再throwしているものは対象外（bodyが単一returnかつ戻り値がそれらの空値のみを狙う）。
    'no-restricted-syntax': ['error',
      {
        selector: "CatchClause > BlockStatement[body.length=1] > ReturnStatement:matches([argument.type='ArrayExpression'][argument.elements.length=0], [argument.type='ObjectExpression'][argument.properties.length=0], [argument.type='Literal'][argument.raw='null'], [argument.type='Identifier'][argument.name='undefined'], :not([argument]))",
        message: 'catchでエラーを握りつぶして空配列/null/空オブジェクト/undefined/空returnを返さないこと。取得失敗が0件表示に偽装される。ログ・通知・再throwで表面化させるか、握りつぶす正当な理由をコメントで明記のうえ該当行のみ eslint-disable-next-line で個別に許可すること。',
      },
      // ルールC: Promise .catch(() => 空fallback) の握りつぶしを禁止（式本体：() => [] / null / undefined / ({}) / {}）。
      // 取得失敗が0件表示や無反応に偽装される事故を予防する。named handler・本体2文以上・非空値返却は対象外。
      {
        selector: "CallExpression[callee.property.name='catch'] > ArrowFunctionExpression:matches([body.type='ArrayExpression'][body.elements.length=0], [body.type='ObjectExpression'][body.properties.length=0], [body.type='Literal'][body.raw='null'], [body.type='Identifier'][body.name='undefined'], [body.type='BlockStatement'][body.body.length=0])",
        message: 'Promiseの.catchでエラーを握りつぶして空配列/null/空オブジェクト/undefinedを返さないこと。取得失敗が0件表示や無反応に偽装される。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。',
      },
      // ルールD: Promise .catch(() => { return 空fallback }) のブロック本体で単一returnの握りつぶしを禁止。
      {
        selector: "CallExpression[callee.property.name='catch'] > ArrowFunctionExpression > BlockStatement[body.length=1] > ReturnStatement:matches([argument.type='ArrayExpression'][argument.elements.length=0], [argument.type='ObjectExpression'][argument.properties.length=0], [argument.type='Literal'][argument.raw='null'], [argument.type='Identifier'][argument.name='undefined'], :not([argument]))",
        message: 'Promiseの.catchでの握りつぶし返却（空配列/null/空オブジェクト/undefined/空return）を禁止。ログ・通知・再throwで表面化させるか、正当な理由をコメントで明記のうえ該当行を個別にeslint-disableすること。',
      },
    ],
  },
})
