import withNuxt from './.nuxt/eslint.config.mjs'

export default withNuxt({
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
    ],
  },
})
