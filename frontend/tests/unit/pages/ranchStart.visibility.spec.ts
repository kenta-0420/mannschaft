// @vitest-environment nuxt
// 実際のRanchPage/Pinia/command memoryを用い、外部HTTPだけを隔離する未実測草稿。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import RanchPage from '~/pages/my/ranch/index.vue'
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
const wrappers: { unmount: () => void }[] = []
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
const state: RanchState = {
 featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'DISABLED', shopAvailable: false,
 owner: { id: '11111111-1111-4111-8111-111111111111', status: 'ACTIVE', balance: '0', version: '1' },
 dinosaur: { id: '22222222-2222-4222-8222-222222222222', speciesKey: null, variantKey: null, habitat: null, stage: 'EGG', name: null, xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: { startedAt: '2026-10-04T00:00:00Z', readyAt: '2026-10-11T00:00:00Z', crackStage: 'INTACT', hatchReady: false, hatchedAt: null } },
 settings: { isVisible: false, viewMode: 'ROOM', renderStyle: 'PIXEL', motionMode: 'REDUCED', isSoundEnabled: false, soundVolume: 50, version: '0' },
 roomSlots: [{ slotKey: 'SHELF_1', inventoryId: null, version: '0' }, { slotKey: 'SHELF_2', inventoryId: null, version: '0' }, { slotKey: 'SHELF_3', inventoryId: null, version: '0' }], serverTime: '2026-10-04T00:00:00Z', policyVersion: null,
 careBudget: { weekStartsOn: '2026-09-28', remainingXp: '100', weeklyCapXp: '100', awardedXp: '0', amountXp: '20', weekEndsAt: '2026-10-04T15:00:00Z', ruleVersion: 'ranch-development-v1' }, weekBudget: null, assignment: { availableMethods: [], selectionConfirmed: false, confirmedMethod: null },
}
const startKeys: string[] = []
let widgetPuts = 0
let lostResponse = false
let registered = false
let visible = false
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 auth.$reset()
 await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens('A-access', 'A-refresh')
 external.fetch.mockReset(); external.report.mockReset(); startKeys.length = 0
 widgetPuts = 0; lostResponse = false; registered = false; visible = false
 external.fetch.mockImplementation(async (request, options) => {
  const path = new URL(String(request)).pathname
  const method = options?.method ?? 'GET'
  if (path === '/api/v1/me/ranch' && method === 'GET') return json(registered ? { ...state, settings: { ...state.settings, isVisible: visible } } : { ...state, owner: null, dinosaur: null, settings: null, roomSlots: [], careBudget: null, assignment: null })
  if (path === '/api/v1/me/ranch' && method === 'POST') {
   startKeys.push(new Headers(options?.headers).get('Idempotency-Key') ?? '')
   registered = true
   if (lostResponse && startKeys.length === 1) throw new TypeError('SYNTHETIC_ACK_LOST')
   return json(state)
  }
  if (path === '/api/v1/dashboard/widgets' && method === 'GET') return json([{ widgetKey: 'PERSONAL_DINOSAUR_RANCH', visible, sortOrder: 0 }])
  if (path === '/api/v1/dashboard/widgets' && method === 'PUT') {
   widgetPuts += 1
   if (widgetPuts === 1) return new Response(JSON.stringify({ error: { code: 'COMMON_001', message: 'SYNTHETIC_VISIBILITY_FAILED' } }), { status: 500, headers: { 'Content-Type': 'application/json' } })
   visible = true
   return json({})
  }
  throw new Error(`UNEXPECTED_TRANSPORT ${method} ${path}`)
 })
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); vi.restoreAllMocks() })
async function click(wrapper: VueWrapper, key: string) {
 const button = wrapper.findAll('button').find(item => item.text() === useNuxtApp().$i18n.t(key) && !item.element.closest('[data-testid="load-error-state"]'))
 if (!button) throw new Error(`BUTTON_NOT_FOUND ${key}`)
 await button.trigger('click'); await flushPromises()
}
describe('開始成功後の明示dashboard表示と専用再試行（未実測）', () => {
 it.each([false, true])('POST応答喪失=%sでも成功後PUT1、PUT失敗は表示だけretryし新POST0', async responseLost => {
  lostResponse = responseLost
  const wrapper = await mountSuspended(RanchPage)
  wrappers.push(wrapper); await flushPromises()
  await click(wrapper, 'ranch.start')
  if (responseLost) {
   expect(startKeys).toHaveLength(1)
   await click(wrapper, 'ranch.retry')
   expect(startKeys).toHaveLength(2)
   expect(startKeys[0]).toBe(startKeys[1])
  }
  expect(widgetPuts).toBe(1)
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.settings.visibilityFailed'))
  const postsBeforeVisibilityRetry = startKeys.length
  await click(wrapper, 'ranch.retry')
  expect(widgetPuts).toBe(2)
  expect(startKeys).toHaveLength(postsBeforeVisibilityRetry)
  expect(registered).toBe(true)
  expect(visible).toBe(true)
 })
})