import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { Component } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-261007-2053 AC-9b: i18n の初期化に失敗していても error.vue は
 * 汎用フォールバック文言と復帰操作（ホームへ戻る）を描画する。
 *
 * useI18n() が例外を投げる／$t が例外を投げる／$t が未定義、のいずれでも
 * error.vue 自体が落ちて真っ白（または Nuxt 既定の英語エラー）にならないこと。
 * 生のキー文字列（error_page.xxx）も表示しない。
 */

const LONG = 300_000

const { clearErrorMock, i18nMode } = vi.hoisted(() => ({
  clearErrorMock: vi.fn(),
  i18nMode: { value: 'throw' as 'throw' | 'undefined-t' },
}))

mockNuxtImport('clearError', () => clearErrorMock)
mockNuxtImport('useI18n', () => () => {
  if (i18nMode.value === 'throw') throw new Error('i18n is not initialized')
  return { t: undefined, locale: { value: undefined } }
})

const ERROR_PAGE_PATH = '~/error.vue'

async function loadErrorPage(): Promise<Component> {
  const mod: { default?: Component } = await import(/* @vite-ignore */ ERROR_PAGE_PATH)
  if (!mod.default) throw new Error('error.vue の default export がありません')
  return mod.default
}

describe('CMP-261007-2053 error.vue の i18n 失敗時フォールバック（AC-9b）', () => {
  beforeEach(() => {
    clearErrorMock.mockReset()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  const cases = [
    {
      name: 'useI18n と $t が例外を投げる',
      mode: 'throw' as const,
      mocks: {
        $t: () => {
          throw new Error('i18n is not initialized')
        },
      },
    },
    { name: 'useI18n の t と $t が未定義の', mode: 'undefined-t' as const, mocks: { $t: undefined } },
  ]

  for (const c of cases) {
    it(
      `AC-9b: ${c.name}場合でも汎用フォールバック文言と「ホームへ戻る」を描画し、押すと clearError({ redirect: "/" })`,
      async () => {
        i18nMode.value = c.mode
        const ErrorPage = await loadErrorPage()
        const wrapper = await mountSuspended(ErrorPage, {
          props: { error: { statusCode: 404, message: 'internal detail' } },
          global: { mocks: c.mocks },
        })
        const title = wrapper.find('[data-testid="error-page-title"]')
        expect(title.exists()).toBe(true)
        expect(title.text().trim()).not.toBe('')
        expect(wrapper.text()).not.toContain('error_page.')
        expect(wrapper.text()).not.toContain('internal detail')
        const home = wrapper.find('[data-testid="error-page-home"]')
        expect(home.exists()).toBe(true)
        expect(home.text().trim()).not.toBe('')
        await home.trigger('click')
        expect(clearErrorMock).toHaveBeenCalledWith({ redirect: '/' })
      },
      LONG,
    )
  }
})
