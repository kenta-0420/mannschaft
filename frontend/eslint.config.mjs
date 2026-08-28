import withNuxt from './.nuxt/eslint.config.mjs'
import { swallowCatchRestrictions } from './eslint-rules/swallow-catch-rules.mjs'

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
    // ルールB〜G: catchでサイレントなfallback値を返すだけの握りつぶしを禁止
    // 取得失敗が空配列/null/空オブジェクト/undefined/空return、および
    // 「中身が空のオブジェクト」（{ data: [] } 等・Issue #2770）で0件表示に偽装される事故を予防する。
    // catch内でログ・通知・再throwしているものは対象外。
    // セレクタの正本と各ルールの説明は eslint-rules/swallow-catch-rules.mjs を参照。
    'no-restricted-syntax': ['error', ...swallowCatchRestrictions],
  },
})
