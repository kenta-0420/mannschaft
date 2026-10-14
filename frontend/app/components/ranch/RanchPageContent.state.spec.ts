// @vitest-environment nuxt
// 実ページ・Widget・private state・本人Piniaを使い、外部HTTPと周辺store・エラー報告を隔離する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import RanchPage from '~/pages/my/ranch/index.vue'
import WidgetRanch from '~/components/widgets/WidgetRanch.vue'
import RanchWidgetContent from '~/components/ranch/RanchWidgetContent.vue'
import RanchStatusPanel from '~/components/ranch/RanchStatusPanel.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { RanchState } from '~/types/ranch'

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
const wrappers: VueWrapper[] = []
const user = (id: number) => ({ id, email: 'synthetic@example.invalid', fullName: 'Synthetic', profileImageUrl: null })
// JSON transportにより、各GETは独立snapshotとなる。共有mutable objectは返さない。
const json = (data: unknown) => new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
function initialState(id: number): RanchState {
  return {
    featureStatus: 'AVAILABLE', deliveryPaused: false, rewardsStatus: 'DISABLED', shopAvailable: false,
    owner: { id: `synthetic-owner-${id}`, status: 'ACTIVE', balance: '0', version: '1' },
    dinosaur: { id: `synthetic-dinosaur-${id}`, speciesKey: null, variantKey: null, habitat: null, stage: 'BABY', name: `Synthetic-${id}`, xp: '0', nextStageXp: null, version: '1', namedAt: null, speciesCatalogVersion: null, egg: null, affinityBand: 'NEUTRAL' },
    settings: { isVisible: true, viewMode: 'ROOM', renderStyle: 'PIXEL', motionMode: 'STOPPED', isSoundEnabled: false, soundVolume: 0, version: '1' },
    roomSlots: [], serverTime: '2026-10-04T00:00:00Z', policyVersion: null,
    careBudget: { weekStartsOn: '2026-09-28', remainingXp: '100', weeklyCapXp: '100', awardedXp: '0', amountXp: '20', weekEndsAt: '2026-10-04T15:00:00Z', ruleVersion: 'synthetic-rule' },
    weekBudget: null, assignment: null,
  }
}
let serverState: RanchState
let pauseVersion: string | undefined
let holdRead: ((snapshot: Response) => Promise<Response>) | undefined
let failRead = false
let reads = 0
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  auth.$reset()
  await auth.setUser(user(1)); auth.setTokens('A-access', 'A-refresh')
  serverState = initialState(1); pauseVersion = undefined; holdRead = undefined; failRead = false; reads = 0
  external.fetch.mockReset(); external.report.mockReset()
  external.fetch.mockImplementation(async (request, options) => {
    const path = new URL(String(request)).pathname
    const method = options?.method ?? 'GET'
    if (path === '/api/v1/me/ranch' && method === 'GET') {
      reads += 1
      if (failRead) return new Response(JSON.stringify({ error: { code: 'COMMON_001', message: 'SYNTHETIC_READ_FAILED' } }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      const snapshot = json(serverState)
      return holdRead ? holdRead(snapshot) : snapshot
    }
    if (path.endsWith('/feeding')) {
      if (!serverState.owner || !serverState.dinosaur || !serverState.careBudget) throw new Error('INCOMPLETE_FIXTURE')
      serverState.owner.version = '2'; serverState.dinosaur.xp = '20'
      serverState.careBudget.remainingXp = '80'; serverState.careBudget.awardedXp = '20'
      return json({ commandId: 'synthetic-feed', dinosaurId: serverState.dinosaur.id, gainedXp: '20', isGrowthCapped: false, stageAfter: 'BABY', costPoints: '0', completedAt: serverState.serverTime })
    }
    if (path.endsWith('/interactions')) {
      if (!serverState.owner || !serverState.dinosaur) throw new Error('INCOMPLETE_FIXTURE')
      serverState.owner.version = '3'; serverState.dinosaur.affinityBand = 'WARM'
      return json({ commandId: 'synthetic-touch', dinosaurId: serverState.dinosaur.id, affinityBand: 'WARM', affinityChanged: true, reactionKey: 'DINOSAUR_TOUCH', completedAt: serverState.serverTime })
    }
    if (path.endsWith('/pause')) {
      pauseVersion = JSON.parse(String(options?.body)).version as string
      if (!serverState.owner) throw new Error('INCOMPLETE_FIXTURE')
      serverState.owner.status = 'PAUSED'; serverState.owner.version = '4'
      return json(serverState.owner)
    }
    throw new Error(`UNEXPECTED_TRANSPORT ${method} ${path}`)
  })
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); vi.restoreAllMocks() })
async function mountPage() {
  const wrapper = await mountSuspended(RanchPage, { global: { stubs: { DinosaurScene: true } } })
  wrappers.push(wrapper); await flushPromises()
  return wrapper
}
async function click(wrapper: VueWrapper, key: string) {
  const button = wrapper.findAll('button').find(item => item.text() === useNuxtApp().$i18n.t(key))
  if (!button) throw new Error(`BUTTON_NOT_FOUND ${key}`)
  await button.trigger('click'); await flushPromises()
}

describe('牧場ページ内のprivate state同期', () => {
  it('初回GET待機中にWidgetやようすのprivate表示を作らない', async () => {
    let release: (() => void) | undefined
    holdRead = snapshot => new Promise<Response>(done => { release = () => done(snapshot) })
    try {
      const wrapper = await mountPage()
      expect(release).toBeDefined()
      expect(wrapper.findComponent(RanchStatusPanel).exists()).toBe(false)
      expect(wrapper.findComponent(RanchWidgetContent).exists()).toBe(false)
      release?.(); await flushPromises()
      expect(wrapper.findComponent(RanchStatusPanel).exists()).toBe(true)
      expect(wrapper.findComponent(RanchWidgetContent).exists()).toBe(true)
      expect(reads).toBe(1)
    } finally { release?.(); await flushPromises() }
  })

  it('ごはんとTOUCH後にようすが更新され、続くpauseは最新versionを送る', async () => {
    const wrapper = await mountPage()
    const t = useNuxtApp().$i18n.t
    expect(wrapper.getComponent(RanchStatusPanel).text()).toContain('0 XP')

    await click(wrapper, 'ranch.care.food')
    const status = wrapper.getComponent(RanchStatusPanel)
    expect(status.text()).toContain('20 XP')
    expect(status.text()).toContain(t('ranch.status.careRemaining', { remaining: '80', cap: '100' }))
    expect(wrapper.getComponent(RanchWidgetContent).text()).toContain(t('ranch.care.fed', { xp: '20' }))
    await click(wrapper, 'ranch.care.touch')
    expect(status.text()).toContain(t('ranch.affinity.WARM'))
    await click(wrapper, 'ranch.pause')
    expect(pauseVersion).toBe('3')
  })

  it('操作後GET待機でもWidgetを維持し、version更新後の成功feedbackを失わない', async () => {
    const wrapper = await mountPage()
    const widget = wrapper.getComponent(RanchWidgetContent).vm
    let release: (() => void) | undefined
    holdRead = snapshot => new Promise<Response>(done => { release = () => done(snapshot) })
    try {
      await click(wrapper, 'ranch.care.food')
      expect(release).toBeDefined()
      expect(wrapper.getComponent(RanchWidgetContent).vm).toBe(widget)
      expect(wrapper.getComponent(RanchStatusPanel).text()).toContain('0 XP')
      const t = useNuxtApp().$i18n.t
      for (const key of ['ranch.care.food', 'ranch.care.touch', 'ranch.pause']) {
        const button = wrapper.findAll('button').find(item => item.text() === t(key))
        expect(button?.attributes('disabled')).toBeDefined()
      }
      release?.(); await flushPromises()
      expect(wrapper.getComponent(RanchWidgetContent).vm).toBe(widget)
      expect(wrapper.getComponent(RanchWidgetContent).text()).toContain(useNuxtApp().$i18n.t('ranch.care.fed', { xp: '20' }))
      expect(wrapper.getComponent(RanchStatusPanel).text()).toContain('20 XP')
    } finally { release?.(); await flushPromises() }
  })

  it('更新GET失敗ではprivate表示を消し、errorを再試行できる', async () => {
    const wrapper = await mountPage()
    failRead = true
    await click(wrapper, 'ranch.admin.refresh')
    expect(wrapper.findComponent(RanchStatusPanel).exists()).toBe(false)
    expect(wrapper.findComponent(RanchWidgetContent).exists()).toBe(false)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
  })

  it('本人変更でAのstateとfeedbackを破棄し、Bの新scopeを取得する', async () => {
    const wrapper = await mountPage()
    await click(wrapper, 'ranch.care.food')
    const widgetA = wrapper.getComponent(RanchWidgetContent).vm
    serverState = initialState(2)
    await useAuthStore().setUser(user(2)); await flushPromises()
    expect(wrapper.getComponent(RanchWidgetContent).vm).not.toBe(widgetA)
    expect(wrapper.getComponent(RanchStatusPanel).text()).toContain('0 XP')
    expect(wrapper.getComponent(RanchWidgetContent).text()).not.toContain(useNuxtApp().$i18n.t('ranch.care.fed', { xp: '20' }))
    useAuthStore().$reset(); await flushPromises()
    expect(wrapper.findComponent(RanchStatusPanel).exists()).toBe(false)
    expect(wrapper.findComponent(RanchWidgetContent).exists()).toBe(false)
  })

  it('page providerのないdashboard Widgetは独立stateを取得する', async () => {
    const first = await mountSuspended(WidgetRanch, { global: { stubs: { DinosaurScene: true } } })
    const second = await mountSuspended(WidgetRanch, { global: { stubs: { DinosaurScene: true } } })
    wrappers.push(first, second); await flushPromises()
    expect(reads).toBe(2)
    await click(first, 'ranch.care.food')
    expect(first.text()).toContain(useNuxtApp().$i18n.t('ranch.care.fed', { xp: '20' }))
    expect(reads).toBe(3)
    expect(first.getComponent({ name: 'DinosaurScene' }).props('dinosaur').xp).toBe('20')
    expect(second.getComponent({ name: 'DinosaurScene' }).props('dinosaur').xp).toBe('0')
    expect(second.getComponent(RanchWidgetContent).text()).not.toContain(useNuxtApp().$i18n.t('ranch.care.fed', { xp: '20' }))
  })
})
