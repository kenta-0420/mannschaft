import Aura from '@primeuix/themes/aura'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },

  app: {
    head: {
      charset: 'utf-8',
      viewport: 'width=device-width, initial-scale=1',
      link: [{ rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' }],
    },
    pageTransition: { name: 'page-fade' },
  },
  future: {
    compatibilityVersion: 4,
  },

  components: [{ path: '~/components', pathPrefix: false }],

  devServer: {
    host: '0.0.0.0',
  },

  modules: [
    '@nuxtjs/i18n',
    '@primevue/nuxt-module',
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    '@vueuse/nuxt',
    '@nuxt/image',
    '@nuxt/eslint',
    '@vite-pwa/nuxt',
  ],

  pwa: {
    registerType: 'autoUpdate',
    manifest: {
      name: 'Mannschaft',
      short_name: 'Mannschaft',
      description: '汎用組織管理プラットフォーム',
      lang: 'ja',
      theme_color: '#3B82F6',
      background_color: '#ffffff',
      display: 'standalone',
      start_url: '/',
      icons: [
        { src: '/icons/icon-192x192.png', sizes: '192x192', type: 'image/png' },
        { src: '/icons/icon-512x512.png', sizes: '512x512', type: 'image/png' },
        {
          src: '/icons/icon-512x512.png',
          sizes: '512x512',
          type: 'image/png',
          purpose: 'maskable',
        },
      ],
    },
    workbox: {
      navigateFallback: '/',
      globPatterns: ['**/*.{js,css,html,png,svg,ico,woff2}'],
      runtimeCaching: [
        {
          urlPattern: /^https?:\/\/.*\/api\/v1\/.*$/,
          handler: 'StaleWhileRevalidate' as const,
          method: 'GET',
          options: {
            cacheName: 'api-cache',
            expiration: { maxEntries: 200, maxAgeSeconds: 86400 },
            cacheableResponse: { statuses: [0, 200] },
          },
        },
        {
          urlPattern: /\.(?:png|jpg|jpeg|svg|gif|webp)$/,
          handler: 'CacheFirst' as const,
          options: {
            cacheName: 'image-cache',
            expiration: { maxEntries: 300, maxAgeSeconds: 604800 },
            cacheableResponse: { statuses: [0, 200] },
          },
        },
        {
          urlPattern: /\.(?:woff|woff2|ttf|eot)$/,
          handler: 'CacheFirst' as const,
          options: {
            cacheName: 'font-cache',
            expiration: { maxEntries: 20, maxAgeSeconds: 2592000 },
          },
        },
      ],
    },
  },

  css: ['~/assets/css/main.css'],

  runtimeConfig: {
    public: {
      apiBase: 'http://localhost:8080',
    },
  },

  i18n: {
    locales: [
      {
        code: 'ja',
        language: 'ja',
        name: '日本語',
        files: [
          'ja/common.json',
          'ja/auth.json',
          'ja/validation.json',
          'ja/landing.json',
          'ja/action_memo.json',
          'ja/pwa.json',
          'ja/recruitment.ts',
          'ja/quick_memo.json',
          'ja/equipment.json',
          'ja/friends.json',
          'ja/announcement.json',
          'ja/profile_media.json',
          'ja/appearance.json',
          'ja/event.json',
        ],
      },
      {
        code: 'en',
        language: 'en',
        name: 'English',
        files: [
          'en/common.json',
          'en/auth.json',
          'en/validation.json',
          'en/landing.json',
          'en/action_memo.json',
          'en/pwa.json',
          'en/recruitment.ts',
          'en/quick_memo.json',
          'en/equipment.json',
          'en/friends.json',
          'en/announcement.json',
          'en/profile_media.json',
          'en/appearance.json',
          'en/event.json',
        ],
      },
      {
        code: 'zh',
        language: 'zh',
        name: '中文（简体）',
        files: [
          'zh/common.json',
          'zh/auth.json',
          'zh/validation.json',
          'zh/landing.json',
          'zh/action_memo.json',
          'zh/pwa.json',
          'zh/recruitment.ts',
          'zh/quick_memo.json',
          'zh/equipment.json',
          'zh/friends.json',
          'zh/announcement.json',
          'zh/profile_media.json',
          'zh/appearance.json',
          'zh/event.json',
        ],
      },
      {
        code: 'ko',
        language: 'ko',
        name: '한국어',
        files: [
          'ko/common.json',
          'ko/auth.json',
          'ko/validation.json',
          'ko/landing.json',
          'ko/action_memo.json',
          'ko/pwa.json',
          'ko/recruitment.ts',
          'ko/quick_memo.json',
          'ko/equipment.json',
          'ko/friends.json',
          'ko/announcement.json',
          'ko/profile_media.json',
          'ko/appearance.json',
          'ko/event.json',
        ],
      },
      {
        code: 'es',
        language: 'es',
        name: 'Español',
        files: [
          'es/common.json',
          'es/auth.json',
          'es/validation.json',
          'es/landing.json',
          'es/action_memo.json',
          'es/pwa.json',
          'es/recruitment.ts',
          'es/quick_memo.json',
          'es/equipment.json',
          'es/friends.json',
          'es/announcement.json',
          'es/profile_media.json',
          'es/appearance.json',
          'es/event.json',
        ],
      },
      {
        code: 'de',
        language: 'de',
        name: 'Deutsch',
        files: [
          'de/common.json',
          'de/auth.json',
          'de/validation.json',
          'de/landing.json',
          'de/action_memo.json',
          'de/pwa.json',
          'de/recruitment.ts',
          'de/quick_memo.json',
          'de/equipment.json',
          'de/friends.json',
          'de/announcement.json',
          'de/profile_media.json',
          'de/appearance.json',
          'de/event.json',
        ],
      },
    ],
    defaultLocale: 'ja',
    strategy: 'no_prefix',
    lazy: true,
    restructureDir: false,
    bundle: {
      optimizeTranslationDirective: false,
    },
    langDir: 'locales/',
    detectBrowserLanguage: {
      useCookie: false,
      redirectOn: 'root',
    },
  },

  primevue: {
    autoImport: true,
    components: {
      prefix: '',
    },
    options: {
      ripple: true,
      inputVariant: 'filled',
      theme: {
        preset: Aura,
        options: {
          prefix: 'p',
          darkModeSelector: '.p-dark',
          cssLayer: false,
        },
      },
    },
  },

  vite: {
    optimizeDeps: {
      // date-holidays は pure ESM パッケージのため、Vite が事前バンドルしないと
      // dev server の SSR コンテキストでモジュール評価が失敗する
      include: ['date-holidays', 'dexie'],
    },
  },
})
