// Lighthouse CI 設定ファイル
// PR ごとに Core Web Vitals を計測し、パフォーマンス劣化を早期検知する。
// 公開ページ（F19.1）が SEO に乗るため、スコア回帰を CI で防ぐ。
//
// しきい値について:
//   初期は緩め（warn のみ）で設定し、ベースラインを把握してから引き締める。
//   error に格上げする際は別 PR で閾値を修正すること。

module.exports = {
  ci: {
    collect: {
      // Nuxt 3 の静的プレビューサーバーを起動して計測
      startServerCommand: 'npm run preview',
      startServerReadyPattern: 'Listening',
      startServerReadyTimeout: 60000,
      url: [
        'http://localhost:3000/',
        'http://localhost:3000/login',
      ],
      numberOfRuns: 1,
      settings: {
        // デスクトップ相当でスロットリング（モバイルは CI マシンでは不安定）
        preset: 'desktop',
        // CI 環境（GPU なし・sandbox 制限あり）向けフラグ
        chromeFlags: '--no-sandbox --headless --disable-gpu',
      },
    },
    assert: {
      assertions: {
        // カテゴリスコア — 初期しきい値（緩め）
        'categories:performance':    ['warn', { minScore: 0.6 }],
        'categories:accessibility':  ['warn', { minScore: 0.8 }],
        'categories:best-practices': ['warn', { minScore: 0.8 }],
        'categories:seo':            ['warn', { minScore: 0.8 }],
        // Core Web Vitals
        'first-contentful-paint':    ['warn', { maxNumericValue: 3000 }],
        'largest-contentful-paint':  ['warn', { maxNumericValue: 4000 }],
        'cumulative-layout-shift':   ['warn', { maxNumericValue: 0.1 }],
        'total-blocking-time':       ['warn', { maxNumericValue: 600 }],
      },
    },
    upload: {
      // 無料の一時パブリックストレージにレポートをアップロード
      // PR コメントにスコアリンクが自動付与される
      target: 'temporary-public-storage',
    },
  },
};
