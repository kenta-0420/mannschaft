// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import ja from '../locales/ja/tournament.json'
import en from '../locales/en/tournament.json'
import zh from '../locales/zh/tournament.json'
import ko from '../locales/ko/tournament.json'
import es from '../locales/es/tournament.json'
import de from '../locales/de/tournament.json'
import {
  tournamentErrorCases,
  tournamentErrorTranslations,
} from '../../tests/unit/helpers/tournamentErrorContract'
import { useErrorHandler } from './useErrorHandler'

const messages = { ja, en, zh, ko, es, de }
type Locale = keyof typeof messages
const i18n = createI18n({ legacy: false, locale: 'ja', fallbackLocale: false, messages })
const missingKeys = new Set<string>()
const notifyError = vi.fn()
const captureQuiet = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => i18n.global.t(key),
  te: (key: string) => !missingKeys.has(key) && i18n.global.te(key),
}))
mockNuxtImport('useNotification', () => () => ({ error: notifyError }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet }))

beforeEach(() => {
  vi.clearAllMocks()
  missingKeys.clear()
  i18n.global.locale.value = 'ja'
  vi.stubGlobal('useI18n', () => ({
    t: (key: string) => i18n.global.t(key),
    te: (key: string) => !missingKeys.has(key) && i18n.global.te(key),
  }))
  vi.stubGlobal('useNotification', () => ({ error: notifyError }))
  vi.stubGlobal('useErrorReport', () => ({ captureQuiet }))
})
afterEach(() => vi.unstubAllGlobals())

describe('CMP-261002-0743 handleApiErrorの大会ロケール契約', () => {
  describe.each(Object.keys(messages) as Locale[])('%s', (locale) => {
    it.each(tournamentErrorCases)(
      'T2 %sはBE日本語messageより利用言語を通知する',
      (_name, code, _key) => {
        i18n.global.locale.value = locale
        const error = { data: { error: { code, message: 'BEの日本語の理由' } } }

        useErrorHandler().handleApiError(error)

        const index = tournamentErrorCases.findIndex(([, candidate]) => candidate === code)
        expect(notifyError).toHaveBeenCalledExactlyOnceWith(
          'dialog.error',
          tournamentErrorTranslations[locale][index],
        )
        expect(captureQuiet).toHaveBeenCalledExactlyOnceWith(error, { context: undefined })
      },
    )
  })

  it.each(['COMMON_001', 'TOUR_018', 'TOUR_029', 'TOUR_999', 'UNKNOWN', 'toString', '__proto__'])(
    'T3 対象外%sの具体的な理由を保持する',
    (code) => {
      useErrorHandler().handleApiError({ data: { error: { code, message: '具体的なBE理由' } } })
      expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', '具体的なBE理由')
    },
  )

  it('T3 対象コードでもキー欠落時はBEのfallbackを保持する', () => {
    missingKeys.add('tournament.entry.error.TOUR_019')
    useErrorHandler().handleApiError({
      data: { error: { code: 'TOUR_019', message: 'BE fallback' } },
    })
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'BE fallback')
  })

  it.each(['', undefined])('T3 空コード%sは従来の不明エラーへ解決する', (code) => {
    useErrorHandler().handleApiError({ data: { error: { code, message: '採用しない理由' } } })
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'error.unknown')
  })

  it('T3 キーもfallbackもないコードは従来の不明エラーへ解決する', () => {
    useErrorHandler().handleApiError({ data: { error: { code: 'UNKNOWN' } } })
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'error.unknown')
  })
})
