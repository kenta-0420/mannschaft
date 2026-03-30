import Aura from '@primeuix/themes/aura'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },
  future: {
    compatibilityVersion: 4,
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

  css: ['primeicons/primeicons.css', '~/assets/css/main.css'],

  runtimeConfig: {
    public: {
      apiBase: 'http://localhost:8080',
    },
  },

  i18n: {
    locales: [
      { code: 'ja', language: 'ja', name: '日本語' },
      { code: 'en', language: 'en', name: 'English' },
      { code: 'zh', language: 'zh', name: '中文（简体）' },
      { code: 'ko', language: 'ko', name: '한국어' },
      { code: 'es', language: 'es', name: 'Español' },
      { code: 'de', language: 'de', name: 'Deutsch' },
    ],
    defaultLocale: 'ja',
    strategy: 'no_prefix',
    lazy: true,
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
