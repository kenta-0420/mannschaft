// @vitest-environment nuxt
// 実画面と composable を通し、HTTP 境界だけを差し替える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import AssignmentPage from '~/pages/my/ranch/assignment.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { RanchState } from '~/types/ranch'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async original => {
 const module = await original<typeof import('ofetch')>()
 return { ...module, ofetch: module.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const wrappers: { unmount: () => void }[] = []
let state: RanchState
let failState: boolean
const json = (data: unknown, status = 200) => new Response(JSON.stringify({ data }), { status, headers: { 'Content-Type': 'application/json' } })
beforeEach(async () => {
 setActivePinia(useNuxtApp().$pinia)
 const auth = useAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 auth.$reset()
 await auth.setUser({ id: 1, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
 auth.setTokens('synthetic-access', 'synthetic-refresh')
 state = { approvedVisualsAvailable: true, featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'DISABLED', shopAvailable: false,
  owner: { id: 'owner', status: 'ACTIVE', balance: '0', version: '1' },
  dinosaur: { id: '11111111-1111-4111-8111-111111111111', speciesKey: null, variantKey: null, habitat: null, stage: 'EGG', name: null, xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: null },
  settings: null, roomSlots: [], serverTime: '2026-11-08T00:00:00Z', policyVersion: null, careBudget: null, weekBudget: null,
  assignment: { availableMethods: ['HABITAT_RANDOM'], selectionConfirmed: false, confirmedMethod: null } }
 failState = false
 external.fetch.mockReset().mockImplementation(async (request, options) => {
  const path = new URL(String(request)).pathname
  if (path === '/api/v1/me/ranch' && (!options?.method || options.method === 'GET')) return json(state, failState ? 503 : 200)
  throw new Error(`UNEXPECTED_ASSIGNMENT_TRANSPORT ${path}`)
 })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); useAuthStore().$reset(); vi.restoreAllMocks() })
async function mountPage() {
 const wrapper = await mountSuspended(AssignmentPage)
 wrappers.push(wrapper); await flushPromises()
 return wrapper
}
describe('選び方の確定状態', () => {
 it('公開済みの確定卵は確定を伝え、再抽選しない', async () => {
  state.assignment!.selectionConfirmed = true
  const wrapper = await mountPage()
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.assignment.confirmed'))
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.unavailable'))
  const buttons = wrapper.findAll('button').filter(button => ['LAND', 'SEA', 'AIR'].some(habitat => button.text() === useNuxtApp().$i18n.t(`ranch.habitat.${habitat}`)))
  expect(buttons).toHaveLength(3)
  expect(buttons.every(button => button.attributes('disabled') !== undefined)).toBe(true)
  await buttons[0]!.trigger('click'); await flushPromises()
  expect(external.fetch).toHaveBeenCalledTimes(1)
 })
 it('未確定で利用可能なら三つの選択肢を有効にする', async () => {
  const wrapper = await mountPage()
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.assignment.confirmed'))
  const buttons = wrapper.findAll('button').filter(button => ['LAND', 'SEA', 'AIR'].some(habitat => button.text() === useNuxtApp().$i18n.t(`ranch.habitat.${habitat}`)))
  expect(buttons).toHaveLength(3)
  expect(buttons.every(button => button.attributes('disabled') === undefined)).toBe(true)
 })
 it('公開停止では確定値があっても従来の準備中を示す', async () => {
  state.approvedVisualsAvailable = false; state.assignment!.selectionConfirmed = true
  const wrapper = await mountPage()
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.unavailable'))
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.assignment.confirmed'))
 })
 it('割当情報が未取得なら確定とせず操作を無効にする', async () => {
  state.assignment = null
  const wrapper = await mountPage()
  expect(wrapper.text()).toContain(useNuxtApp().$i18n.t('ranch.unavailable'))
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.assignment.confirmed'))
  const buttons = wrapper.findAll('button').filter(button => ['LAND', 'SEA', 'AIR'].some(habitat => button.text() === useNuxtApp().$i18n.t(`ranch.habitat.${habitat}`)))
  expect(buttons).toHaveLength(3)
  expect(buttons.every(button => button.attributes('disabled') !== undefined)).toBe(true)
 })
 it('state取得失敗はエラーとし確定を示さない', async () => {
  failState = true; state.assignment!.selectionConfirmed = true
  const wrapper = await mountPage()
  expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
  expect(wrapper.text()).not.toContain(useNuxtApp().$i18n.t('ranch.assignment.confirmed'))
 })
})
