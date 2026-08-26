import os from 'node:os'
import { defineVitestConfig } from '@nuxt/test-utils/config'

// ワーカー過剰供給の是正は多コア機のみに適用する。
//
// 是正が要る理由は「ワーカーがそれぞれ独立に Nuxt 環境を構築して CPU/メモリを
// 奪い合う」ことであり、これはワーカー数が多いほど悪化する。逆に言えば
// GitHub Actions の標準ランナー（ubuntu-latest = 4コア/16GB）のような少コア機では
// 奪い合う相手が少なく、絞る理由がない。むしろ 25% を適用するとワーカーが
// 1〜2 本まで落ちて実行が直列化し、累積テスト時間（実測 2125 秒）を
// まともに受けることになる。絞ることが害にしかならない。
//
// よって閾値をコア数 8 に置き、8 コア以上でのみ 25% を適用する。
// 8 は「22コア機では絞る必要がある／4コアのCIランナーでは絞ってはならない」という
// 両端の実測の間を安全側に取った値であり、8 コア前後での最適値は未実測。
// 割合 25% は 22コア機での実測（6/22 ≒ 27%）を丸めたもの。
//
// なお少コア機で本当に絞らなくてよいかは CI での実測で確かめる必要がある
// （2026-08-09 時点では未実測。CI 結線後の初回実行が最初の実測になる）。
const cpuCount = os.cpus().length
const maxWorkers = cpuCount >= 8 ? '25%' : undefined

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/unit/**/*.spec.ts', 'app/**/*.spec.ts'],
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
    // 6/22 ≒ 27% を根拠に、割合指定にする（vitest 4.x は maxWorkers に
    // `number | string`（パーセント文字列）を受け付ける。node_modules/vitest の
    // 型定義 dist/chunks/reporters.d.*.d.ts と CLI ヘルプ文言
    // "Maximum number or percentage of workers to run tests in" で確認済み）。
    // ただしこの割合指定は多コア機でのみ有効化する（ファイル冒頭のコメント・
    // cpuCount/maxWorkers の算出を参照）。CIランナー等の少コア機では
    // undefined を渡し vitest 既定の並列数のまま動かす。
    // hookTimeout/testTimeout の値そのものは変更していない（症状隠しをしない）。
    maxWorkers,
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
