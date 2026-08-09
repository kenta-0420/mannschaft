import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/unit/**/*.spec.ts'],
    globals: true,
    hookTimeout: 120000,
    // ワーカー過剰供給の是正（Issue #2609）
    // 22コア環境で vitest 既定の並列数（=CPUコア数）を使うと、各ワーカーが
    // それぞれ独立に Nuxt 環境を構築しようとして互いのCPU/メモリを奪い合い、
    // 一部ファイルが 120 秒の hookTimeout に到達してファイルごと崩れる
    // （中のテストは一度も実行されずに skip 扱いになる）。
    // 実測(A/B, 全217ファイル走): 既定(22並列)=46失敗/171成功、548件skip。
    //   --maxWorkers=6 = 1失敗/216成功、skip 0件。実時間は683秒→667秒で悪化せず、
    //   累積transform時間は5832秒→509秒（約11分の1）に短縮。
    // 6/22 ≒ 27% を根拠に、CIランナー等のコア数が少ない環境でも過剰供給を
    // 起こさないよう割合指定にする（vitest 4.x は maxWorkers に
    // `number | string`（パーセント文字列）を受け付ける。node_modules/vitest の
    // 型定義 dist/chunks/reporters.d.*.d.ts と CLI ヘルプ文言
    // "Maximum number or percentage of workers to run tests in" で確認済み）。
    // hookTimeout/testTimeout の値そのものは変更していない（症状隠しをしない）。
    maxWorkers: '25%',
    setupFiles: ['./tests/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['app/**/*.{ts,vue}'],
      exclude: ['app/**/*.d.ts'],
    },
    alias: {
      // @vite-pwa/nuxt の仮想モジュールは Vitest 環境では解決できないためモック
      'virtual:pwa-register/vue': new URL(
        './tests/mocks/pwa-register-vue.ts',
        import.meta.url,
      ).pathname.replace(/^\/([A-Z]:)/, '$1'),
    },
  },
})
