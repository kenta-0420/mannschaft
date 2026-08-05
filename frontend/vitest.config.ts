import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/unit/**/*.spec.ts'],
    globals: true,
    hookTimeout: 120000,
    setupFiles: ['./tests/setup.ts'],
    // 変換コストを hookTimeout / testTimeout の計測枠の外で先払いする（#2609）
    globalSetup: ['./tests/global-setup.ts'],
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
