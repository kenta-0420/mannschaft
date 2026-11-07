// @vitest-environment nuxt
// 実Widget/state/commandを通し、HTTPだけを外部境界で差し替える。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia } from 'pinia'
import { defineComponent, h, watch, type PropType } from 'vue'
import { useNuxtApp } from '#app'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import RanchWidgetContent from './RanchWidgetContent.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import type { DinosaurSummary, RanchState } from '~/types/ranch'

const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), report: vi.fn() }))
vi.mock('ofetch', async (original) => {
  const module = await original<typeof import('ofetch')>()
  return { ...module, ofetch: module.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({
  resolveApiBaseUrl: () => 'http://synthetic.invalid',
}))
mockNuxtImport('useErrorReport', () => () => ({
  capture: external.report,
  captureQuiet: external.report,
}))
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
type Reaction = { key: string; dinosaurId: string; sequence: number; soundToken: number | null }
const observed: { reaction: Reaction; stage: string }[] = []
const cancel = vi.fn()
const SceneStub = defineComponent({
  name: 'DinosaurScene',
  props: {
    dinosaur: { type: Object as PropType<DinosaurSummary>, required: true },
    reaction: { type: Object as PropType<Reaction | null>, default: null },
  },
  setup(props, { expose }) {
    expose({ prepareTouchSound: () => 17, cancelTouchSound: cancel })
    watch(
      () => props.reaction,
      (value) => {
        if (value) observed.push({ reaction: value, stage: props.dinosaur.stage })
      },
      { flush: 'sync' },
    )
    return () => h('div')
  },
})
const wrappers: VueWrapper[] = []
const id = '11111111-1111-4111-8111-111111111111'
const user = (number: number) => ({
  id: number,
  email: 'synthetic@example.invalid',
  fullName: 'Synthetic',
  profileImageUrl: null,
})
function fixture(): RanchState {
  return {
    featureStatus: 'AVAILABLE',
    deliveryPaused: false,
    rewardsStatus: 'DISABLED',
    shopAvailable: false,
    owner: { id: 'owner', status: 'ACTIVE', balance: '0', version: '1' },
    dinosaur: {
      id,
      speciesKey: 'DEV_CLIP_TYRANNOSAURUS',
      variantKey: 'DEV_BASE_16_CLIPS_V1',
      speciesCatalogVersion: '3',
      habitat: 'LAND',
      stage: 'BABY',
      name: 'Synthetic',
      namedAt: null,
      xp: '0',
      nextStageXp: '40',
      version: '1',
      egg: null,
    },
    settings: {
      isVisible: true,
      viewMode: 'ROOM',
      renderStyle: 'PIXEL',
      motionMode: 'NORMAL',
      isSoundEnabled: false,
      soundVolume: 0,
      version: '1',
    },
    roomSlots: [],
    serverTime: '2026-10-21T00:00:00Z',
    policyVersion: null,
    careBudget: null,
    weekBudget: null,
    assignment: null,
  }
}
const json = (data: unknown) =>
  new Response(JSON.stringify({ data }), { headers: { 'Content-Type': 'application/json' } })
let server: RanchState,
  commandId: string,
  ackId: string,
  stageAfter: 'BABY' | 'JUVENILE' | 'ADULT',
  gainedXp: string,
  reject: boolean
let holdCommand: ((response: Response) => Promise<Response>) | null,
  holdRead: ((response: Response) => Promise<Response>) | null
beforeEach(async () => {
  setActivePinia(useNuxtApp().$pinia)
  const auth = useAuthStore()
  vi.spyOn(auth, 'clearUserCaches').mockResolvedValue()
  auth.$reset()
  await auth.setUser(user(1))
  auth.setTokens('A-access', 'A-refresh')
  server = fixture()
  commandId = 'feed-1'
  ackId = id
  stageAfter = 'BABY'
  gainedXp = '0'
  reject = false
  holdCommand = null
  holdRead = null
  observed.length = 0
  cancel.mockClear()
  external.fetch.mockReset()
  external.fetch.mockImplementation(async (request, options) => {
    const path = new URL(String(request)).pathname
    if (path === '/api/v1/me/ranch' && (!options?.method || options.method === 'GET')) {
      const response = json(server)
      return holdRead ? holdRead(response) : response
    }
    if (path.endsWith('/feeding') || path.endsWith('/interactions')) {
      if (reject)
        return new Response(
          JSON.stringify({ error: { code: 'COMMON_001', message: 'REJECTED' } }),
          { status: 409, headers: { 'Content-Type': 'application/json' } },
        )
      const response = path.endsWith('/feeding')
        ? json({
            commandId,
            dinosaurId: ackId,
            gainedXp,
            isGrowthCapped: false,
            stageAfter,
            costPoints: '0',
            completedAt: server.serverTime,
          })
        : json({
            commandId,
            dinosaurId: ackId,
            reactionKey: 'DINOSAUR_TOUCH',
            affinityBand: 'NEUTRAL',
            affinityChanged: false,
            completedAt: server.serverTime,
          })
      return holdCommand ? holdCommand(response) : response
    }
    throw new Error('UNEXPECTED_TRANSPORT ' + path)
  })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})
async function mountWidget() {
  const wrapper = await mountSuspended(RanchWidgetContent, {
    global: { stubs: { DinosaurScene: SceneStub } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
async function click(wrapper: VueWrapper, key = 'ranch.care.food') {
  const button = wrapper
    .findAll('button')
    .find((value) => value.text() === useNuxtApp().$i18n.t(key))
  if (!button) throw new Error('BUTTON_MISSING ' + key)
  await button.trigger('click')
  await flushPromises()
}
describe('CLIP06/07 persisted ACKからのclip通知', () => {
  it('XP0の成功feedもreload完了後に同IDのfeedを1回通知する', async () => {
    const wrapper = await mountWidget()
    let release: (() => void) | undefined
    holdRead = (response) =>
      new Promise((resolve) => {
        release = () => resolve(response)
      })
    await click(wrapper)
    expect(observed).toHaveLength(0)
    expect(release).toBeDefined()
    release?.()
    await flushPromises()
    expect(observed).toHaveLength(1)
    expect(observed[0]?.reaction).toMatchObject({
      key: 'DINOSAUR_FEED',
      dinosaurId: id,
      soundToken: null,
    })
  })
  it('growth後はcurrentstageを反映してからそのstageのfeedを通知する', async () => {
    const wrapper = await mountWidget()
    stageAfter = 'JUVENILE'
    server.dinosaur!.stage = 'JUVENILE'
    await click(wrapper)
    expect(observed).toHaveLength(1)
    expect(observed[0]?.stage).toBe('JUVENILE')
    expect(wrapper.getComponent(SceneStub).props('dinosaur').stage).toBe('JUVENILE')
  })
  it('pendingの間は予測通知せず拒否ACKでは通知しない', async () => {
    const wrapper = await mountWidget()
    let release: (() => void) | undefined
    holdCommand = (response) =>
      new Promise((resolve) => {
        release = () => resolve(response)
      })
    await click(wrapper)
    expect(observed).toHaveLength(0)
    release?.()
    await flushPromises()
    expect(observed).toHaveLength(1)
    reject = true
    commandId = 'feed-reject'
    await click(wrapper)
    expect(observed).toHaveLength(1)
  })
  it.each(['wrong-id', 'obsolete-stage'])(
    '%s応答は新個体/成長状態へ反応を送らない',
    async (mode) => {
      const wrapper = await mountWidget()
      if (mode === 'wrong-id') ackId = '22222222-2222-4222-8222-222222222222'
      else server.dinosaur!.stage = 'ADULT'
      await click(wrapper)
      expect(observed).toHaveLength(0)
    },
  )
  it('actor変更後の旧成功応答を拒否し新actorへ通知しない', async () => {
    const wrapper = await mountWidget()
    let release: (() => void) | undefined
    holdCommand = (response) =>
      new Promise((resolve) => {
        release = () => resolve(response)
      })
    await click(wrapper)
    await useAuthStore().setUser(user(2))
    release?.()
    await flushPromises()
    expect(observed).toHaveLength(0)
  })
  it('保存済みcommandIdの再通知は反応sequenceを増やさない', async () => {
    const wrapper = await mountWidget()
    await click(wrapper)
    expect(observed).toHaveLength(1)
    await click(wrapper)
    expect(observed).toHaveLength(1)
  })
  it('PAUSEDのTOUCHは成功ACK＋sameIDで音tokenを保持したhappy通知へ接続する', async () => {
    server.owner!.status = 'PAUSED'
    commandId = 'touch-1'
    const wrapper = await mountWidget()
    await click(wrapper, 'ranch.care.touch')
    expect(observed).toHaveLength(1)
    expect(observed[0]?.reaction).toMatchObject({
      key: 'DINOSAUR_TOUCH',
      dinosaurId: id,
      soundToken: 17,
    })
  })
})
