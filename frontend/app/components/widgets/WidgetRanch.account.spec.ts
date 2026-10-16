// @vitest-environment nuxt
// 保持Widget、本物Pinia/feature API/command memoryを使い、外部HTTP transportだけを遅延させる。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import WidgetRanch from '~/components/widgets/WidgetRanch.vue'
import { useAuthStore as actualUseAuthStore } from '~/stores/useAuthStore'
import type { RanchState } from '~/types/ranch'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
 const original = await importOriginal<typeof import('ofetch')>()
 return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report }))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const wrappers: { unmount: () => void }[] = []
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
function state(id: number): RanchState {
 return {
  featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'ENABLED', shopAvailable: false,
  owner: { id: `synthetic-owner-${id}`, status: 'ACTIVE', balance: '0', version: '1' },
  dinosaur: { id: `synthetic-dinosaur-${id}`, speciesKey: null, variantKey: null, habitat: null, stage: 'BABY', name: 'Synthetic', xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: null },
  settings: { isVisible: true, viewMode: 'ROOM', renderStyle: 'PIXEL', motionMode: 'STOPPED', isSoundEnabled: false, soundVolume: 0, version: '1' },
  roomSlots: [], serverTime: '2026-10-04T00:00:00Z', policyVersion: null,
  careBudget: null, weekBudget: null, assignment: null,
 }
}
const getOwners: number[] = []
beforeEach(async () => {
 // pluginが実際にcaptureした同じNuxtApp Piniaを使う。別factoryへの差替えはしない。
 setActivePinia(useNuxtApp().$pinia)
 const auth = actualUseAuthStore()
 vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
 await auth.setUser(user(1))
 auth.setTokens('A-access', 'A-refresh')
 external.fetch.mockReset(); external.report.mockReset(); getOwners.length = 0
 external.fetch.mockImplementation(async (request, options) => {
  if (String(request).endsWith('/api/v1/me/ranch') && (options?.method ?? 'GET') === 'GET') {
   const id = actualUseAuthStore().user?.id
   if (id === undefined) throw new Error('UNAUTHENTICATED_FIXTURE_GET')
   getOwners.push(id)
   return json(state(id))
  }
  if (String(request).endsWith('/feeding')) return json({ commandId: 'synthetic-command', dinosaurId: 'synthetic-dinosaur-1', gainedXp: '10', isGrowthCapped: false, stageAfter: 'BABY', costPoints: '0', completedAt: '2026-10-04T00:00:00Z' })
  throw new Error(`UNEXPECTED_TRANSPORT ${String(request)}`)
 })
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function mountWidget() {
 const wrapper = await mountSuspended(WidgetRanch)
 wrappers.push(wrapper)
 await flushPromises()
 return wrapper
}
async function switchToB() {
 const auth = actualUseAuthStore()
 await auth.setUser(user(2)); auth.setTokens('B-access', 'B-refresh')
 await flushPromises()
}
async function showCurrentOwner(wrapper: Awaited<ReturnType<typeof mountWidget>>) {
 const refresh = wrapper.findAll('button').find(item => item.find('.pi-refresh').exists())
 if (!refresh) throw new Error('REFRESH_BUTTON_NOT_FOUND')
 await refresh.trigger('click'); await flushPromises()
 expect(getOwners).toContain(2)
 expect(wrapper.find('[role="status"]').exists()).toBe(true)
}
describe('DOM保持した本人恐竜WidgetのGETと操作feedback境界', () => {
 it('初期nullでは匿名HTTP0、loginBでfresh private scopeのGET1', async () => {
  actualUseAuthStore().$reset()
  await mountWidget()
  expect(external.fetch).not.toHaveBeenCalled()
  await switchToB()
  expect(getOwners).toEqual([2])
 })
 it('activeのA→BでB本人stateを一度取得する', async () => {
  await mountWidget()
  expect(getOwners).toEqual([1])
  await switchToB()
  expect(getOwners).toEqual([1, 2])
 })
 it('inactive中のA→BはGET0、activation後にBを一度取得する', async () => {
  const wrapper = await mountWidget()
  await wrapper.setProps({ active: false })
  await switchToB()
  expect(getOwners).toEqual([1])
  await wrapper.setProps({ active: true }); await flushPromises()
  expect(getOwners).toEqual([1, 2])
 })
 it('Aの既存care feedbackをBへ残さない', async () => {
  const wrapper = await mountWidget()
  const label = useNuxtApp().$i18n.t('ranch.care.food')
  const button = wrapper.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('FEED_BUTTON_NOT_FOUND')
  await button.trigger('click'); await flushPromises()
  expect(wrapper.get('[role="status"]').text()).not.toBe('')
  await switchToB()
  await showCurrentOwner(wrapper)
  expect(wrapper.get('[role="status"]').text()).toBe('')
 })
 it('A careの遅延応答後にBへ成功/失敗feedbackを表示しない', async () => {
  const wrapper = await mountWidget()
  let resolve: ((response: Response) => void) | undefined
  let markStarted: (() => void) | undefined
  const started = new Promise<void>(done => { markStarted = done })
  external.fetch.mockImplementationOnce(() => { markStarted?.(); return new Promise<Response>(done => { resolve = done }) })
  const label = useNuxtApp().$i18n.t('ranch.care.food')
  const button = wrapper.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('FEED_BUTTON_NOT_FOUND')
  await button.trigger('click')
  await started
  try {
   await switchToB()
   await showCurrentOwner(wrapper)
   resolve?.(json({ commandId: 'synthetic-command', dinosaurId: 'synthetic-dinosaur-1', gainedXp: '10' }))
   await flushPromises()
   expect(wrapper.get('[role="status"]').text()).toBe('')
  } finally {
   resolve?.(json({ commandId: 'synthetic-cleanup', dinosaurId: 'synthetic-dinosaur-1', gainedXp: '0' }))
   await flushPromises()
  }
 })
 it('異個体TOUCH応答は準備音をcloseし、音constructor失敗でも保存HTTPを止めない', async () => {
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.stubGlobal('IntersectionObserver', class {
   constructor(private callback: IntersectionObserverCallback) {}
   observe() { this.callback([{ isIntersecting: true }] as IntersectionObserverEntry[], {} as IntersectionObserver) }
   unobserve() {}
   disconnect() {}
  })
  const closed = vi.fn(async () => {})
  class AudioFixture {
   close = closed
   resume = vi.fn(async () => {})
  }
  vi.stubGlobal('AudioContext', AudioFixture)
  const ownId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  let touches = 0
  external.fetch.mockImplementation(async (request, options) => {
   if (String(request).endsWith('/api/v1/me/ranch') && (options?.method ?? 'GET') === 'GET') {
    const value = state(1)
    if (!value.dinosaur || !value.settings) throw new Error('SOUND_STATE_FIXTURE_MISSING')
    value.dinosaur.id = ownId
    value.settings.motionMode = 'REDUCED'; value.settings.isSoundEnabled = true; value.settings.soundVolume = 50
    return json(value)
   }
   if (String(request).endsWith('/interactions') && options?.method === 'POST') {
    touches += 1
    return json({ commandId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', dinosaurId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', reactionKey: 'DINOSAUR_TOUCH', affinityBand: 'NEUTRAL', affinityChanged: false, completedAt: '2026-10-04T00:00:00Z' })
   }
   throw new Error('UNEXPECTED_SOUND_TRANSPORT')
  })
  const wrapper = await mountWidget()
  const label = useNuxtApp().$i18n.t('ranch.care.touch')
  const button = wrapper.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('TOUCH_BUTTON_MISSING')
  await button.trigger('click'); await flushPromises()
  expect(touches).toBe(1); expect(closed).toHaveBeenCalledOnce()
  let constructors = 0
  vi.stubGlobal('AudioContext', class { constructor() { constructors += 1; throw new Error('SYNTHETIC_AUDIO_CONSTRUCTOR_FAILURE') } close() { return Promise.resolve() } })
  await button.trigger('click'); await flushPromises()
  expect(constructors).toBe(1); expect(touches).toBe(2)
  expect(wrapper.get('[role="status"]').text()).toContain(useNuxtApp().$i18n.t('ranch.care.touched'))
 })

})
