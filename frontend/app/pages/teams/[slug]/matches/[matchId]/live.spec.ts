import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { useRuntimeConfig, useRouter } from '#imports'
import { useAuthStore } from '~/stores/useAuthStore'
import { createError } from 'h3'
import type { MatchEventResponse } from '~/types/match'
import { resolveSportModule } from '~/composables/match/sport/sportModuleRegistry'
import LivePage from './live.vue'

const matchId = '00000000-0000-4000-8000-000000000124'
const detailPath = `/api/v1/organizations/124/teams/124/matches/${matchId}`
const errors: unknown[] = []
let canRecord = true
let matchStatus = 'SCHEDULED'
let savedEvents: MatchEventResponse[] = []
let eventsFail = false
let eventRequests = 0
let eventResponses = 0
const eventPlans: { gate: Promise<void>; status?: number; events?: MatchEventResponse[] }[] = []
const releaseEventGates: (() => void)[] = []
const postEvent = vi.fn(() => ({ data: { id: 'period-start-test' } }))
let detailReached = false
let releaseDetail: () => void
let detailGate: Promise<void>
let originalBase: string
let originalUser: ReturnType<typeof useAuthStore>['user']
const releaseWakeLock = vi.fn(async () => undefined)
const requestWakeLock = vi.fn(async () => ({
  release: releaseWakeLock,
  addEventListener: vi.fn(),
}))
const testUser = {
  id: 124,
  email: 'live-init@example.test',
  fullName: '記録テスト',
  profileImageUrl: null,
}

beforeAll(async () => {
  // 初回のルート/競技チャンク変換は環境準備として待ち、ケース間に持ち越さない。
  const auth = useAuthStore()
  const previous = auth.user
  auth.user = testUser
  try {
    await useRouter().replace(`/teams/live-init-test/matches/${matchId}/live?org=124`)
    await resolveSportModule('SOCCER')
  } finally {
    auth.user = previous
  }
})

// 自前の composable は実体を通し、HTTP とブラウザのハードウェア境界だけを置き換える。
registerEndpoint('/api/v1/me/teams', () => ({
  data: [{ id: 124, slug: 'live-init-test', organizationId: 124 }],
}))
registerEndpoint(detailPath, async () => {
  detailReached = true
  await detailGate
  return {
    data: {
      id: matchId,
      sport: 'SOCCER',
      status: matchStatus,
      homeAway: 'HOME',
      canRecordTimeline: canRecord,
    },
  }
})
registerEndpoint(`/api/v1/organizations/124/matches/${matchId}/events`, async () => {
  const plan = eventPlans.shift()
  const status = plan?.status ?? (eventsFail ? 503 : undefined)
  const events = plan?.events ?? savedEvents
  eventRequests += 1
  await plan?.gate
  eventResponses += 1
  if (status) throw createError({ statusCode: status, statusMessage: 'events unavailable' })
  return {
    data: { events, derivedHomeScore: 0, derivedAwayScore: 0 },
  }
})
registerEndpoint('/api/v1/teams/live-init-test/members', () => ({ data: [] }))
registerEndpoint(`/api/v1/organizations/124/matches/${matchId}/events`, {
  method: 'POST',
  handler: postEvent,
})

beforeEach(() => {
  errors.length = 0
  canRecord = true
  matchStatus = 'SCHEDULED'
  savedEvents = []
  eventsFail = false
  eventRequests = 0
  eventResponses = 0
  eventPlans.length = 0
  detailReached = false
  detailGate = new Promise((resolve) => {
    releaseDetail = resolve
  })
  originalBase = useRuntimeConfig().public.apiBase
  useRuntimeConfig().public.apiBase = ''
  originalUser = useAuthStore().user
  useAuthStore().user = testUser
  // STOMP 自体は実装を通し、外部ネットワークのソケットのみ接続待機状態に固定する。
  vi.stubGlobal(
    'WebSocket',
    class {
      readyState = 0
      onclose: ((event: CloseEvent) => void) | null = null
      close() {
        this.readyState = 3
        this.onclose?.(new CloseEvent('close'))
      }
      send() {}
    },
  )
  Object.defineProperty(navigator, 'wakeLock', {
    configurable: true,
    value: { request: requestWakeLock },
  })
})

afterEach(() => {
  releaseEventGates.splice(0).forEach((release) => release())
  releaseDetail()
  useRuntimeConfig().public.apiBase = originalBase
  useAuthStore().user = originalUser
  Reflect.deleteProperty(navigator, 'wakeLock')
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

async function mountPage() {
  const wrapper = await mountSuspended(LivePage, {
    route: `/teams/live-init-test/matches/${matchId}/live?org=124`,
    global: {
      config: {
        errorHandler: (error) => {
          errors.push(error)
          console.error(error)
        },
      },
    },
  })
  // この到達が、同期 setup の実 I18n/Toast/Pinia と HTTP fixture の対照になる。
  await vi.waitFor(() => expect(detailReached).toBe(true))
  expect(errors).toEqual([])
  releaseDetail()
  return wrapper
}

describe('ライブ記録画面の非同期初期化', () => {
  it('イベント取得待ちで離脱した後の成功応答はtimerや画面資源を作らない', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    releaseEventGates.push(release)
    eventPlans.push({
      gate,
      events: [{ id: 'late', sortSeq: 1, eventType: 'OTHER', period: 'FIRST_HALF', minute: 17 }],
    })
    const intervals = vi.spyOn(globalThis, 'setInterval')
    const addListener = vi.spyOn(window, 'addEventListener')
    const wrapper = await mountPage()
    await vi.waitFor(() => expect(eventRequests).toBe(1))
    wrapper.unmount()
    intervals.mockClear()
    addListener.mockClear()

    release()
    await vi.waitFor(() => expect(eventResponses).toBe(1))
    await flushPromises()
    try {
      expect(intervals.mock.calls.filter(([, delay]) => delay === 1000)).toHaveLength(0)
      expect(requestWakeLock).not.toHaveBeenCalled()
      expect(addListener.mock.calls.filter(([name]) => name === 'online')).toHaveLength(0)
    } finally {
      // red時の既知の離脱後tickもテストから漏らさず、受入assertはそのまま失敗させる。
      intervals.mock.calls.forEach(([, delay], index) => {
        if (delay === 1000) clearInterval(intervals.mock.results[index]!.value)
      })
    }
  })

  it('同時再試行の古い失敗応答は新しい成功セッションを停止しない', async () => {
    eventsFail = true
    const wrapper = await mountPage()
    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true),
    )
    const errorView = wrapper.findComponent({ name: 'DashboardErrorState' })
    const retry = errorView.vm.$.vnode.props!.onRetry as () => Promise<void>
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    releaseEventGates.push(release)
    eventsFail = false
    eventPlans.push(
      { gate, status: 403 },
      {
        gate: Promise.resolve(),
        events: [{ id: 'fresh', sortSeq: 1, eventType: 'OTHER', period: 'FIRST_HALF', minute: 17 }],
      },
    )
    const previousResponses = eventResponses
    const previousRequests = eventRequests
    // 再試行ボタンに実際に結線された同一handlerを、古いHTTPが未完了の間に再実行する。
    void retry()
    await vi.waitFor(() => expect(eventRequests).toBe(previousRequests + 1))
    void retry()
    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'MatchTimerControls' }).props('currentMinute')).toBe(17),
    )

    release()
    await vi.waitFor(() => expect(eventResponses).toBe(previousResponses + 2))
    await flushPromises()

    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MatchTimerControls' }).props('running')).toBe(true)
    expect(wrapper.findComponent({ name: 'MatchTimerControls' }).props('currentMinute')).toBe(17)
    wrapper.unmount()
  })

  it('再訪では保存済み後半58分を表示し、復元だけでは境界POSTを送らない', async () => {
    matchStatus = 'IN_PROGRESS'
    savedEvents = [
      {
        id: 'start',
        sortSeq: 1,
        eventType: 'PERIOD_START',
        period: 'SECOND_HALF',
        minute: 45,
      },
      {
        id: 'recorded',
        sortSeq: 2,
        eventType: 'OTHER',
        period: 'SECOND_HALF',
        minute: 58,
      },
    ]
    const wrapper = await mountPage()

    await vi.waitFor(() => {
      const controls = wrapper.findComponent({ name: 'MatchTimerControls' })
      expect(controls.exists()).toBe(true)
      expect(controls.props('state')).toBe('SECOND_HALF')
      expect(controls.props('currentMinute')).toBe(58)
    })
    expect(wrapper.findComponent({ name: 'MatchScoreboard' }).props('clock')).toMatch(/^13:0[0-9]$/)
    expect(postEvent).not.toHaveBeenCalled()
    expect(errors).toEqual([])
    wrapper.unmount()
  })

  it('イベント取得失敗を開始前に偽装せず、再試行で同じ初期化を完了する', async () => {
    eventsFail = true
    const wrapper = await mountPage()

    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true),
    )
    expect(wrapper.findComponent({ name: 'MatchTimerControls' }).exists()).toBe(false)
    expect(requestWakeLock).not.toHaveBeenCalled()
    expect(postEvent).not.toHaveBeenCalled()
    eventsFail = false
    savedEvents = [
      {
        id: 'resume',
        sortSeq: 1,
        eventType: 'OTHER',
        period: 'FIRST_HALF',
        minute: 17,
      },
    ]
    await wrapper.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')

    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'MatchTimerControls' }).props('currentMinute')).toBe(17),
    )
    expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
    expect(postEvent).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存境界が途中失敗した履歴は復元エラーを示し記録操作を出さない', async () => {
    savedEvents = [
      {
        id: 'incomplete',
        sortSeq: 1,
        eventType: 'PERIOD_END',
        period: 'EXTRA_FIRST',
        minute: 105,
      },
    ]
    const wrapper = await mountPage()

    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true),
    )
    expect(wrapper.findComponent({ name: 'MatchTimerControls' }).exists()).toBe(false)
    expect(postEvent).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('詳細と競技のロード後に記録操作を表示し、unmount で資源を解放する', async () => {
    const addListener = vi.spyOn(window, 'addEventListener')
    const removeListener = vi.spyOn(window, 'removeEventListener')
    const intervals = vi.spyOn(globalThis, 'setInterval')
    const clearInterval = vi.spyOn(globalThis, 'clearInterval')
    const wrapper = await mountPage()
    await vi.waitFor(() => {
      expect(errors).toEqual([])
      expect(requestWakeLock).toHaveBeenCalledWith('screen')
      expect(wrapper.findComponent({ name: 'MatchTimerControls' }).exists()).toBe(true)
    })
    const online = addListener.mock.calls.find(([name]) => name === 'online')
    expect(online).toBeDefined()
    intervals.mockClear()
    clearInterval.mockClear()
    await wrapper.findComponent({ name: 'MatchTimerControls' }).find('button').trigger('click')
    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'MatchTimerControls' }).props('running')).toBe(true),
    )
    const tick = intervals.mock.calls.findIndex(([, delay]) => delay === 1000)
    expect(tick).toBeGreaterThanOrEqual(0)
    const timerId = intervals.mock.results[tick]!.value
    wrapper.unmount()
    expect(clearInterval).toHaveBeenCalledWith(timerId)
    expect(releaseWakeLock).toHaveBeenCalledOnce()
    expect(removeListener).toHaveBeenCalledWith('online', online![1])
  })

  it('記録権限がない場合は記録セッションと wake lock を作らず観戦へ進む', async () => {
    canRecord = false
    const wrapper = await mountPage()
    await vi.waitFor(() =>
      expect(wrapper.findComponent({ name: 'MatchSpectatorView' }).exists()).toBe(true),
    )
    expect(errors).toEqual([])
    expect(wrapper.findComponent({ name: 'MatchTimerControls' }).exists()).toBe(false)
    expect(requestWakeLock).not.toHaveBeenCalled()
    wrapper.unmount()
    expect(releaseWakeLock).not.toHaveBeenCalled()
  })
})
