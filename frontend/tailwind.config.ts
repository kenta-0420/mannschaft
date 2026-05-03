import type { Config } from 'tailwindcss'
import PrimeUI from 'tailwindcss-primeui'

export default {
  content: [
    './app/components/**/*.{vue,js,ts}',
    './app/layouts/**/*.vue',
    './app/pages/**/*.vue',
    './app/composables/**/*.{js,ts}',
    './app/plugins/**/*.{js,ts}',
    './app/app.vue',
  ],
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: [
          'Noto Sans JP',
          'Yu Gothic UI',
          'Meiryo UI',
          'Hiragino Sans',
          'ui-sans-serif',
          'system-ui',
          'sans-serif',
        ],
      },
    },
  },
  plugins: [PrimeUI],
} satisfies Config
