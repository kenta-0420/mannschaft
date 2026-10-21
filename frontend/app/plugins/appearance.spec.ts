// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useHead as unheadUseHead } from '@unhead/vue'
import { createHead, renderDOMHead } from '@unhead/vue/client'
import { createHead as createServerHead, renderSSRHead } from '@unhead/vue/server'
import type { UseHeadInput } from '@unhead/vue'
import { useAppearanceStore } from '~/stores/useAppearanceStore'

// Nuxt の request/context 境界だけ差し替え、head の解決・DOM patch は実物を使う。
const boundary = vi.hoisted(() => ({ cookie: null as string | null, useHead: vi.fn(), api: vi.fn() }))
vi.mock('#app/composables/cookie', () => ({ useCookie: () => ({ value: boundary.cookie }) }))
vi.mock('#app/composables/head', () => ({ useHead: (input: UseHeadInput) => boundary.useHead(input) }))
vi.mock('~/composables/useApi', () => ({ useApi: () => boundary.api }))

async function runPlugin() {
  vi.stubGlobal('defineNuxtPlugin', (setup: () => void) => setup)
  vi.stubGlobal('useCookie', () => ({ value: boundary.cookie }))
  vi.stubGlobal('useHead', boundary.useHead)
  vi.stubGlobal('useAppearanceStore', useAppearanceStore)
  const { default: plugin } = await import('~/plugins/appearance')
  // Nuxt plugin の setup は今回 app 引数を使わない。
  await (plugin as unknown as () => void)()
}

beforeEach(() => {
  setActivePinia(createPinia())
  boundary.cookie = null
  boundary.useHead.mockReset()
  boundary.api.mockReset()
  localStorage.clear()
  document.documentElement.removeAttribute('class')
  document.documentElement.removeAttribute('style')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe.skipIf(import.meta.server)('appearance: client の head 再描画', () => {
  it.each([
    { theme: 'DARK', bgColor: '#f3efe0', darkBgColor: '#18181b', expected: '#18181b' },
    { theme: 'DARK', bgColor: '#f3efe0', darkBgColor: '#14241c', expected: '#14241c' },
    { theme: 'LIGHT', bgColor: '#ecf7f0', darkBgColor: '#18181b', expected: '#ecf7f0' },
  ] as const)('初期cookieが空でも保存済み $theme / $expected がページhead更新後に残る', async (saved) => {
    const head = createHead({ document })
    boundary.useHead.mockImplementation((input: UseHeadInput) => unheadUseHead(input, { head }))
    await runPlugin()
    await renderDOMHead(head)

    // localStorage復元の後、ログイン時のAPI同期も通す。
    localStorage.setItem('appearance', JSON.stringify(saved))
    const store = useAppearanceStore()
    store.loadFromStorage()
    boundary.api.mockResolvedValue({ data: saved })
    vi.stubGlobal('useApi', () => boundary.api)
    await store.loadFromServer()
    expect(boundary.api).toHaveBeenCalledWith('/api/v1/settings/appearance')
    expect(document.documentElement.style.getPropertyValue('--bg-color')).toBe(saved.expected)

    // Nuxt のページ遷移・タイトル変更と同じく、別entryの更新でhead全体を再patchする。
    const page = unheadUseHead({ title: '牧場' }, { head })
    await renderDOMHead(head)
    page.patch({ title: '牧場の住民' })
    await renderDOMHead(head)

    expect(document.documentElement.style.getPropertyValue('--bg-color')).toBe(saved.expected)
    expect(document.documentElement.classList.contains('dark')).toBe(saved.theme === 'DARK')
    expect(document.documentElement.classList.contains('p-dark')).toBe(saved.theme === 'DARK')
    expect(store.theme).toBe(saved.theme)
    expect(JSON.parse(localStorage.getItem('appearance')!).theme).toBe(saved.theme)
  })
})

describe.skipIf(!import.meta.server)('appearance: SSR cookie の初回描画', () => {
  it.each([
    { cookie: null, expected: '#f3efe0', dark: false },
    { cookie: 'invalid-json', expected: '#f3efe0', dark: false },
    { cookie: JSON.stringify({ theme: 'LIGHT', bgColor: '#ecf7f0' }), expected: '#ecf7f0', dark: false },
    { cookie: JSON.stringify({ theme: 'DARK' }), expected: '#18181b', dark: true },
    { cookie: JSON.stringify({ theme: 'DARK', darkBgColor: '#14241c' }), expected: '#14241c', dark: true },
  ])('cookie=$cookie から $expected をSSRする', async ({ cookie, expected, dark }) => {
    const head = createServerHead()
    boundary.cookie = cookie
    boundary.useHead.mockImplementation((input: UseHeadInput) => unheadUseHead(input, { head }))
    await runPlugin()
    const rendered = await renderSSRHead(head)
    const parsed = new DOMParser().parseFromString(`<html ${rendered.htmlAttrs}><head></head><body></body></html>`, 'text/html')
    expect(parsed.documentElement.style.getPropertyValue('--bg-color')).toBe(expected)
    expect(parsed.documentElement.classList.contains('dark')).toBe(dark)
    expect(parsed.documentElement.classList.contains('p-dark')).toBe(dark)
    expect(useAppearanceStore().theme).toBe(dark ? 'DARK' : 'LIGHT')
  })
})
