// @vitest-environment nuxt
// 実Pinia・command・private GETと装飾画面を使い、外部HTTPのみ合成する。未実測。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import DecorationsPage from '~/pages/my/ranch/decorations.vue'
import type { RanchState } from '~/types/ranch'
import { useAuthStore } from '~/stores/useAuthStore'
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
const state: RanchState = {
 featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'DISABLED', shopAvailable: false,
 owner: { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE', balance: '0', version: '1' },
 dinosaur: { id: '22222222-2222-4222-8222-222222222222', speciesKey: null, variantKey: null, habitat: null, stage: 'EGG', name: null, xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: { startedAt: '2026-10-04T00:00:00Z', readyAt: '2026-10-11T00:00:00Z', crackStage: 'INTACT', hatchReady: false, hatchedAt: null } },
 settings: { isVisible: false, viewMode: 'ROOM', renderStyle: 'PIXEL', motionMode: 'REDUCED', isSoundEnabled: false, soundVolume: 50, version: '0' },
 roomSlots: [{ slotKey: 'SHELF_1', inventoryId: null, version: '0' }, { slotKey: 'SHELF_2', inventoryId: null, version: '0' }, { slotKey: 'SHELF_3', inventoryId: null, version: '0' }], serverTime: '2026-10-04T00:00:00Z', policyVersion: null,
 careBudget: { weekStartsOn: '2026-09-28', remainingXp: '100', weeklyCapXp: '100', awardedXp: '0', amountXp: '20', weekEndsAt: '2026-10-04T15:00:00Z', ruleVersion: 'ranch-development-v1' }, weekBudget: null, assignment: { availableMethods: [], selectionConfirmed: false, confirmedMethod: null },
}

const posts: { key: string; cursor: string; token: string | null }[] = []
const wrappers: VueWrapper[] = []
const stateGets: (string | null)[] = []
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
let responseLost = false
let delayA = false
let resolveA: (() => void) | null = null
async function account(id: number) {
 const auth = useAuthStore()
 await auth.setUser({ id, email: `synthetic${id}@example.invalid`, fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens(id === 1 ? 'A-access' : 'B-access', 'synthetic-refresh')
}
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 auth.$reset()
 await account(1)
 posts.length = 0; stateGets.length = 0; responseLost = false; delayA = false; resolveA = null
 external.fetch.mockReset(); external.report.mockReset()
 external.fetch.mockImplementation(async (request, options) => {
  const path = new URL(String(request)).pathname
  const method = options?.method ?? 'GET'
  if (method === 'GET' && path === '/api/v1/me/ranch') {
   const token = new Headers(options?.headers).get('Authorization')
   stateGets.push(token)
   return json(token === 'Bearer B-access' ? { ...state, owner: { ...state.owner, id: '44444444-4444-4444-8444-444444444444' } } : state)
  }
  if (method === 'GET' && path === '/api/v1/me/ranch/collectibles') return new Response(JSON.stringify({ data: [], meta: { nextCursor: null, hasNext: false, limit: 20 } }), { headers: { 'Content-Type': 'application/json' } })
  if (method === 'POST' && path === '/api/v1/me/ranch/collectibles/sync') {
   const headers = new Headers(options?.headers)
   const body = JSON.parse(String(options?.body)) as { afterAwardId: string }
   posts.push({ key: headers.get('Idempotency-Key') ?? '', cursor: body.afterAwardId, token: headers.get('Authorization') })
   if (responseLost && posts.length === 1) throw new TypeError('SYNTHETIC_ACK_LOST')
   if (delayA && headers.get('Authorization') === 'Bearer A-access') await new Promise<void>(resolve => { resolveA = resolve })
   return json({ commandId: '33333333-3333-4333-8333-333333333333', nextAfterAwardId: '100', processedCount: delayA ? 99 : 100, importedCount: 0, hasNext: body.afterAwardId === '0', completedAt: '2026-10-05T00:00:00Z' })
  }
  throw new Error(`UNEXPECTED_TRANSPORT ${method} ${path}`)
 })
})
afterEach(async () => {
 resolveA?.()
 await flushPromises()
 for (const wrapper of wrappers.splice(0)) wrapper.unmount()
 vi.restoreAllMocks()
})
async function mounted() {
 const wrapper = await mountSuspended(DecorationsPage)
 wrappers.push(wrapper)
 await flushPromises()
 return wrapper
}
async function click(wrapper: VueWrapper, key: string) {
 const button = wrapper.findAll('button').find(item => item.text() === useNuxtApp().$i18n.t(key) && !item.element.closest('[data-testid="load-error-state"]'))
 if (!button) throw new Error(`BUTTON_NOT_FOUND ${key}`)
 await button.trigger('click')
 await flushPromises()
}
describe('本人の明示・有限記念品取込（未実測）', () => {
 it('初回GETは取込0、続きは本人クリックだけで新keyの100件操作', async () => {
  const wrapper = await mounted()
  expect(posts).toHaveLength(0)
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.decorations.slots'))
  await click(wrapper, 'ranch.decorations.importStart')
  expect(posts).toHaveLength(1)
  expect(posts[0]?.cursor).toBe('0')
  await flushPromises()
  expect(posts).toHaveLength(1)
  await click(wrapper, 'ranch.decorations.importContinue')
  expect(posts).toHaveLength(2)
  expect(posts[1]?.cursor).toBe('100')
  expect(posts[1]?.key).not.toBe(posts[0]?.key)
 })
 it('応答不明の再送は同key/body', async () => {
  responseLost = true
  const wrapper = await mounted()
  await click(wrapper, 'ranch.decorations.importStart')
  await click(wrapper, 'ranch.retry')
  expect(posts).toHaveLength(2)
  expect(posts[1]).toEqual(posts[0])
 })
 it('後日の再走査は人の操作でcursor0と新key', async () => {
  const wrapper = await mounted()
  await click(wrapper, 'ranch.decorations.importStart')
  await click(wrapper, 'ranch.decorations.importAgain')
  expect(posts).toHaveLength(2)
  expect(posts[1]?.cursor).toBe('0')
  expect(posts[1]?.key).not.toBe(posts[0]?.key)
 })
 it('Aの遅延結果はBの画面へ反映されずBは新scopeで取得する', async () => {
  delayA = true
  const wrapper = await mounted()
  await click(wrapper, 'ranch.decorations.importStart')
  expect(resolveA).not.toBeNull()
  await account(2)
  await flushPromises()
  resolveA?.()
  await flushPromises()
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.decorations.importResult', { processed: 99, imported: 0 }))
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.decorations.slots'))
  expect(posts).toHaveLength(1)
  expect(posts[0]?.token).toBe('Bearer A-access')
  expect(stateGets.filter(token => token === 'Bearer B-access')).toHaveLength(1)
 })
})