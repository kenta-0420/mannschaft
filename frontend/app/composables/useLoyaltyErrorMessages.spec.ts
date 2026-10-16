// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import { useErrorHandler } from './useErrorHandler'
const notification = vi.hoisted(() => ({ error: vi.fn() }))
const report = vi.hoisted(() => ({ captureQuiet: vi.fn() }))
const i18n = vi.hoisted(() => ({ t: vi.fn(), te: vi.fn() }))
vi.mock('vue-i18n', () => ({ useI18n: () => i18n }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notification }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => report }))
vi.stubGlobal('useNotification', () => notification)
vi.stubGlobal('useErrorReport', () => report)

describe('ポイント業務エラーの実翻訳経路（AC20/31）', () => {
  for (const language of ['ja', 'en', 'zh', 'ko', 'es', 'de']) {
    it(`${language}: LOYALTY全15件と設定競合をサーバー日本語で上書きしない`, () => {
      const translations = JSON.parse(
        readFileSync(new URL(`../locales/${language}/common.json`, import.meta.url), 'utf8'),
      ) as Record<string, unknown>
      const lookup = (key: string) => {
        let current: unknown = translations
        for (const part of key.split('.')) current = (current as Record<string, unknown>)?.[part]
        return typeof current === 'string' ? current : undefined
      }
      i18n.t.mockImplementation((key: string) => lookup(key) ?? key)
      i18n.te.mockImplementation((key: string) => lookup(key) !== undefined)
      vi.stubGlobal('useI18n', () => i18n)
      const handler = useErrorHandler()
      for (const code of [
        ...Array.from({ length: 15 }, (_, i) => `LOYALTY_${String(i + 1).padStart(3, '0')}`),
        'COMMON_003',
        'MEMBERSHIP_003',
      ]) {
        notification.error.mockClear()
        handler.handleApiError(
          { data: { error: { code, message: 'サーバーの日本語メッセージ' } }, statusCode: 409 },
          'team-loyalty',
        )
        const message = notification.error.mock.calls[0]?.[1] as string
        expect(message).toBeTruthy()
        expect(message).not.toBe('サーバーの日本語メッセージ')
        expect(message).not.toMatch(/^(loyalty|loadErrorState|error)\./)
        if (language !== 'ja') expect(message).not.toMatch(/[\u3040-\u30ff]/)
      }
    })
  }
})
