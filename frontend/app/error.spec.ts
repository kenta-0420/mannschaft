import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import type { Component } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#imports'

/**
 * CMP-261007-2053: error.vue（404/500 の言語追従・情報秘匿・復帰導線）。
 *
 * 前提とする実装:
 * - app/error.vue（Nuxt の error page）。props.error を受け取る。
 *   ログイン画面風の中央カード（auth レイアウト相当）。認証ストアに依存しない。
 * - data-testid: error-page（ルート）/ error-page-title / error-page-description / error-page-home
 * - 文言は common.json の error_page.{not_found_title, not_found_description,
 *   generic_title, generic_description, back_home}
 * - 「ホームへ戻る」は clearError({ redirect: '/' })
 * - statusCode 以外の内部情報（message / statusMessage / stack / data）は描画しない
 *
 * 実装前のため error.vue は it 内で dynamic import する。
 * 言語メッセージの初回読み込みは dev ビルドで数十秒かかるため timeout を長く取る。
 */

const LONG = 300_000
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

const { clearErrorMock, authStoreAccess } = vi.hoisted(() => ({
  clearErrorMock: vi.fn(),
  authStoreAccess: vi.fn(),
}))

mockNuxtImport('clearError', () => clearErrorMock)
// AC-9: 認証ストア未初期化でも描画が落ちないこと。error.vue が認証ストアに触れたら即座に例外にする。
mockNuxtImport('useAuthStore', () => () => {
  authStoreAccess()
  throw new Error('auth store is not initialized')
})

const here = dirname(fileURLToPath(import.meta.url))

function errorPageMessages(lang: string): Record<string, string> {
  const json = JSON.parse(readFileSync(resolve(here, 'locales', lang, 'common.json'), 'utf-8')) as Record<string, unknown>
  return (json.error_page ?? {}) as Record<string, string>
}

const ERROR_PAGE_PATH = '~/error.vue'

async function loadErrorPage(): Promise<Component> {
  const mod: { default?: Component } = await import(/* @vite-ignore */ ERROR_PAGE_PATH)
  if (!mod.default) throw new Error('error.vue の default export がありません')
  return mod.default
}

type NuxtErrorLike = {
  statusCode: number
  statusMessage?: string
  message?: string
  stack?: string
  data?: unknown
  url?: string
}

async function mountError(error: NuxtErrorLike) {
  const ErrorPage = await loadErrorPage()
  return mountSuspended(ErrorPage, { props: { error } })
}

async function setLocale(code: string) {
  const i18n = useNuxtApp().$i18n as unknown as { setLocale: (c: string) => Promise<void> }
  await i18n.setLocale(code)
}

const SECRET = {
  message: 'SQLSTATE[42S02] team_secret_table team_id=98765 not visible for org 4321',
  statusMessage: 'Team 98765 of organization 4321 not found',
  stack: 'Error: boom\n    at TeamService.findById (com/mannschaft/app/team/TeamService.java:123)',
  data: { resourceId: 98765, organizationId: 4321 },
}

describe('CMP-261007-2053 error.vue', () => {
  beforeEach(async () => {
    clearErrorMock.mockReset()
    authStoreAccess.mockReset()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    await setLocale('ja')
  }, LONG)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  for (const lang of LANGS) {
    it(
      `AC-7: 404 は ${lang} の error_page.not_found_title で描画され、英語既定「Page not found」を出さない`,
      async () => {
        await setLocale(lang)
        const msg = errorPageMessages(lang)
        expect(msg.not_found_title, `${lang} の not_found_title`).toBeTruthy()
        const wrapper = await mountError({ statusCode: 404, statusMessage: 'Page not found', url: '/no-such-page' })
        expect(wrapper.find('[data-testid="error-page-title"]').text()).toBe(msg.not_found_title)
        expect(wrapper.find('[data-testid="error-page-home"]').text()).toContain(msg.back_home)
        expect(wrapper.text()).not.toMatch(/page not found/i)
      },
      LONG,
    )
  }

  it(
    'AC-7c: 404 で error に message/statusMessage/stack/data を入れても描画されない',
    async () => {
      const wrapper = await mountError({ statusCode: 404, ...SECRET })
      const html = wrapper.html()
      expect(wrapper.find('[data-testid="error-page"]').exists()).toBe(true)
      for (const leak of ['98765', '4321', 'SQLSTATE', 'TeamService', 'team_secret_table', 'organization']) {
        expect(html, `内部情報 ${leak} を出さない`).not.toContain(leak)
      }
    },
    LONG,
  )

  it(
    'AC-7c: 不存在 URL の 404 と他テナント資源の 404 は同じ汎用文言になる',
    async () => {
      const notExist = await mountError({ statusCode: 404, statusMessage: 'Page not found: /no-such-page', url: '/no-such-page' })
      const otherTenant = await mountError({ statusCode: 404, ...SECRET, url: '/teams/other-tenant-team' })
      expect(notExist.find('[data-testid="error-page"]').exists()).toBe(true)
      expect(otherTenant.find('[data-testid="error-page"]').text()).toBe(
        notExist.find('[data-testid="error-page"]').text(),
      )
    },
    LONG,
  )

  it(
    'AC-9: 「ホームへ戻る」で clearError({ redirect: "/" }) が呼ばれる',
    async () => {
      const wrapper = await mountError({ statusCode: 404 })
      const home = wrapper.find('[data-testid="error-page-home"]')
      expect(home.exists()).toBe(true)
      await home.trigger('click')
      expect(clearErrorMock).toHaveBeenCalledTimes(1)
      expect(clearErrorMock).toHaveBeenCalledWith({ redirect: '/' })
    },
    LONG,
  )

  it(
    'AC-9: 認証ストア未初期化（アクセスすると例外）でも描画が落ちず、認証ストアに触れない',
    async () => {
      const wrapper = await mountError({ statusCode: 404 })
      expect(wrapper.find('[data-testid="error-page-title"]').text()).not.toBe('')
      expect(authStoreAccess).not.toHaveBeenCalled()
    },
    LONG,
  )

  it(
    'AC-10: 500 は汎用文言で描画され、statusCode 以外の内部情報（message/stack/statusMessage/data）を出さない',
    async () => {
      const msg = errorPageMessages('ja')
      expect(msg.generic_title, 'ja の generic_title').toBeTruthy()
      const wrapper = await mountError({ statusCode: 500, ...SECRET })
      expect(wrapper.find('[data-testid="error-page-title"]').text()).toBe(msg.generic_title)
      expect(wrapper.find('[data-testid="error-page-title"]').text()).not.toBe(msg.not_found_title)
      const html = wrapper.html()
      for (const leak of ['98765', '4321', 'SQLSTATE', 'TeamService', 'team_secret_table', 'at TeamService']) {
        expect(html, `内部情報 ${leak} を出さない`).not.toContain(leak)
      }
      expect(wrapper.find('[data-testid="error-page-home"]').exists()).toBe(true)
    },
    LONG,
  )

  it.each([400, 403, 502, 503])(
    'AC-10: %i も 404 専用文言ではなく汎用文言になる',
    async (statusCode) => {
      const msg = errorPageMessages('ja')
      const wrapper = await mountError({ statusCode, message: SECRET.message })
      expect(wrapper.find('[data-testid="error-page-title"]').text()).toBe(msg.generic_title)
      expect(wrapper.html()).not.toContain('SQLSTATE')
    },
    LONG,
  )
})
