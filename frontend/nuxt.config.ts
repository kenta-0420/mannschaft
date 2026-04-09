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
  ],

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
        files: ['ja/common.json', 'ja/auth.json', 'ja/validation.json', 'ja/landing.json', 'ja/recruitment.ts'],
      },
      {
        code: 'en',
        language: 'en',
        name: 'English',
        files: ['en/common.json', 'en/auth.json', 'en/validation.json', 'en/landing.json', 'en/recruitment.ts'],
      },
      {
        code: 'zh',
        language: 'zh',
        name: '中文（简体）',
        files: ['zh/common.json', 'zh/auth.json', 'zh/validation.json', 'zh/landing.json', 'zh/recruitment.ts'],
      },
      {
        code: 'ko',
        language: 'ko',
        name: '한국어',
        files: ['ko/common.json', 'ko/auth.json', 'ko/validation.json', 'ko/landing.json', 'ko/recruitment.ts'],
      },
      {
        code: 'es',
        language: 'es',
        name: 'Español',
        files: ['es/common.json', 'es/auth.json', 'es/validation.json', 'es/landing.json', 'es/recruitment.ts'],
      },
      {
        code: 'de',
        language: 'de',
        name: 'Deutsch',
        files: ['de/common.json', 'de/auth.json', 'de/validation.json', 'de/landing.json', 'de/recruitment.ts'],
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
})
