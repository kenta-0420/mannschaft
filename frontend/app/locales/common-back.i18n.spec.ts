// @vitest-environment node
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'

const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
type Locale = (typeof locales)[number]
type CommonMessages = {
  button: Record<string, string>
  common: Record<string, string>
}

// 既存 button.back の訳を基準に、fallback で欠落が隠れないことを検査する。
const expectedBack: Record<Locale, string> = {
  ja: '戻る',
  en: 'Back',
  zh: '返回',
  ko: '뒤로',
  es: 'Volver',
  de: 'Zurück',
}
const localesDir = dirname(fileURLToPath(import.meta.url))
const messages = Object.fromEntries(
  locales.map((locale) => [
    locale,
    JSON.parse(readFileSync(resolve(localesDir, locale, 'common.json'), 'utf8')) as CommonMessages,
  ]),
) as Record<Locale, CommonMessages>

function createLocaleI18n(locale: Locale) {
  return createI18n<[CommonMessages], Locale, false>({
    legacy: false,
    locale,
    fallbackLocale: false,
    messages,
    missingWarn: false,
    fallbackWarn: false,
  })
}

describe('CMP-260930-1535 共通の戻るラベルの実ロケール解決', () => {
  it.each(locales)('%s は既存 button.back の文言を保持する', (locale) => {
    const i18n = createLocaleI18n(locale)
    try {
      expect(i18n.global.te('button.back', locale)).toBe(true)
      expect(i18n.global.t('button.back')).toBe(expectedBack[locale])
    } finally {
      i18n.dispose()
    }
  })

  it.each(locales)('%s は common.back を解決し言語切替にも追従する', (locale) => {
    const i18n = createLocaleI18n(locale)
    try {
      expect(i18n.global.te('common.back', locale)).toBe(true)
      expect(i18n.global.t('common.back')).toBe(expectedBack[locale])

      const nextLocale = locales[(locales.indexOf(locale) + 1) % locales.length]!
      i18n.global.locale.value = nextLocale
      expect(i18n.global.te('common.back', nextLocale)).toBe(true)
      expect(i18n.global.t('common.back')).toBe(expectedBack[nextLocale])
    } finally {
      i18n.dispose()
    }
  })
})
