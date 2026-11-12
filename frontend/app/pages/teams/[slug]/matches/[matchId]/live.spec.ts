import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { useRuntimeConfig, useRouter } from '#imports'
import { useAuthStore } from '~/stores/useAuthStore'
import { resolveSportModule } from '~/composables/match/sport/sportModuleRegistry'
import LivePage from './live.vue'

const matchId = '00000000-0000-4000-8000-000000000124'
const detailPath = `/api/v1/organizations/124/teams/124/matches/${matchId}`
const errors: unknown[] = []
let canRecord = true
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
      status: 'SCHEDULED',
      homeAway: 'HOME',
      canRecordTimeline: canRecord,
    },
  }
})
registerEndpoint(`/api/v1/organizations/124/matches/${matchId}/events`, () => ({
  data: { events: [], derivedHomeScore: 0, derivedAwayScore: 0 },
}))
registerEndpoint('/api/v1/teams/live-init-test/members', () => ({ data: [] }))
registerEndpoint(`/api/v1/organizations/124/matches/${matchId}/events`, {
  method: 'POST',
  handler: () => ({ data: { id: 'period-start-test' } }),
})

beforeEach(() => {
  errors.length = 0
  canRecord = true
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
