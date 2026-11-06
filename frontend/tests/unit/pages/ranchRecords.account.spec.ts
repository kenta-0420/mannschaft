// @vitest-environment nuxt
// 実Pinia・実ページ・実ofetchを使う本人境界の先行試練。外部HTTPだけを合成する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import RecordsPage from '~/pages/my/ranch/records.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { RanchRecord } from '~/types/ranch'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))

const privateA: RanchRecord = {
  id: '11111111-1111-4111-8111-111111111111', kind: 'REWARD', sourceType: 'TIMELINE_ORIGINAL',
  deltaPoints: '981234567', deltaXp: '0', occurredAt: '2026-10-05T00:00:00Z',
  sourceLink: { kind: 'TIMELINE', id: '12', url: '/timeline/12' },
}
const privateB: RanchRecord = {
  id: '22222222-2222-4222-8222-222222222222', kind: 'REWARD', sourceType: 'TIMELINE_ORIGINAL',
  deltaPoints: '892345678', deltaXp: '0', occurredAt: '2026-10-05T00:00:00Z', sourceLink: null,
}
const wrappers: VueWrapper[] = []
const tokens: (string | null)[] = []
let settleA: ((response: Response) => void) | null = null
function response(record: RanchRecord) {
  return new Response(JSON.stringify({ data: [record], meta: { nextCursor: null, hasNext: false, limit: 20 } }), { headers: { 'Content-Type': 'application/json' } })
}
async function account(id: number) {
  const auth = useAuthStore()
  // 実login順に資格を先に設定する。世代更新後の初回GETが古い資格にならない。
  auth.setTokens(id === 1 ? 'A-access' : 'B-access', 'synthetic-refresh')
  await auth.setUser({ id, email: `synthetic${id}@example.invalid`, fullName: 'Synthetic', profileImageUrl: null })
}
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  auth.$reset()
  tokens.length = 0; settleA = null
  external.fetch.mockReset(); external.report.mockReset()
  external.fetch.mockImplementation(async (request, options) => {
    const path = new URL(String(request)).pathname
    if ((options?.method ?? 'GET') !== 'GET' || path !== '/api/v1/me/ranch/records') throw new Error(`UNEXPECTED_TRANSPORT ${path}`)
    const token = new Headers(options?.headers).get('Authorization')
    tokens.push(token)
    if (token === 'Bearer A-access') return await new Promise<Response>(resolve => { settleA = resolve })
    if (token === 'Bearer B-access') return response(privateB)
    throw new Error('UNEXPECTED_ANONYMOUS_TRANSPORT')
  })
})
afterEach(async () => {
  settleA?.(response(privateA))
  await flushPromises()
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})
async function mounted() {
  const wrapper = await mountSuspended(RecordsPage)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function expectOnlyB(wrapper: VueWrapper) {
  expect(wrapper.text()).not.toContain(privateA.deltaPoints)
  expect(wrapper.find('a[href="/timeline/12"]').exists()).toBe(false)
  expect(wrapper.text()).toContain(privateB.deltaPoints)
  expect(tokens.filter(token => token === 'Bearer B-access')).toHaveLength(1)
}

describe('本人記録の保持DOM・正常200境界（先行試練、未実測）', () => {
  it('Aの遅延200をBへ描画せずB本人の記録を一回取得する', async () => {
    await account(1)
    const wrapper = await mounted()
    expect(settleA).not.toBeNull()
    await account(2)
    await flushPromises()
    settleA?.(response(privateA))
    await flushPromises()
    await vi.waitFor(() => expectOnlyB(wrapper))
  })
  it('旧Aの遅延失敗とfinallyが取得済みBの記録を消さない', async () => {
    await account(1)
    const wrapper = await mounted()
    expect(settleA).not.toBeNull()
    await account(2)
    await flushPromises()
    settleA?.(new Response(JSON.stringify({ error: { code: 'RANCH_001', message: 'Synthetic rejection' } }), { status: 403, headers: { 'Content-Type': 'application/json' } }))
    await flushPromises()
    await vi.waitFor(() => expectOnlyB(wrapper))
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
  })
  it('初期未認証はHTTP0で通常B復元後に一回取得する', async () => {
    const wrapper = await mounted()
    expect(tokens).toHaveLength(0)
    await account(2)
    await flushPromises()
    await vi.waitFor(() => expectOnlyB(wrapper))
  })
})
