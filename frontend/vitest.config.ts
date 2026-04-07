import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['tests/unit/**/*.spec.ts'],
    globals: true,
    // Nuxtビルド初回起動に時間がかかるためhookTimeoutを延長
    hookTimeout: 60000,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['app/**/*.{ts,vue}'],
      exclude: ['app/**/*.d.ts'],
    },
  },
})
