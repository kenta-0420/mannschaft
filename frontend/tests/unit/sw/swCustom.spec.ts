// @vitest-environment node
/**
 * CMP-261029-0620: Service Worker（public/sw-custom.js）の push / notificationclick の試験。
 *
 * SW はブラウザの ServiceWorkerGlobalScope で動くため、node:vm で偽の `self` を与えて
 * ファイルをそのまま評価し、登録されたリスナーへイベントを流して検査する。
 * push の入力には BE と共有する見本 JSON（backend/src/test/resources/contracts/web-push-payload.json）を使う（AC-15）。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import vm from 'node:vm'
import { describe, expect, it, vi, type Mock } from 'vitest'

const SW_PATH = resolve(__dirname, '../../../public/sw-custom.js')
const CONTRACT_PATH = resolve(
  __dirname,
  '../../../../backend/src/test/resources/contracts/web-push-payload.json',
)
const ORIGIN = 'https://app.example.com'
const DEFAULT_TITLE = 'Mannschaft'

interface WebPushContract {
  title: string
  body: string
  data: { notification_id: number; action_url: string | null; priority: string }
}
const CONTRACT = JSON.parse(readFileSync(CONTRACT_PATH, 'utf8')) as WebPushContract

interface FakeWindowClient {
  url: string
  focus: Mock<() => Promise<unknown>>
  navigate: Mock<(url: string) => Promise<unknown>>
}

type Listener = (event: Record<string, unknown>) => void

interface Harness {
  listeners: Record<string, Listener[]>
  showNotification: Mock<(title: string, options: Record<string, unknown>) => Promise<void>>
  matchAll: Mock<(options: unknown) => Promise<FakeWindowClient[]>>
  openWindow: Mock<(url: string) => Promise<unknown>>
  fetch: Mock<(...args: unknown[]) => Promise<unknown>>
  fire: (type: string, event: Record<string, unknown>) => Promise<unknown> | undefined
}

function windowClient(url: string): FakeWindowClient {
  const client: FakeWindowClient = {
    url,
    focus: vi.fn(async () => client),
    navigate: vi.fn(async () => client),
  }
  return client
}

function loadSw(windows: FakeWindowClient[] = []): Harness {
  const listeners: Record<string, Listener[]> = {}
  const showNotification = vi.fn(async () => undefined) as Harness['showNotification']
  const matchAll = vi.fn(async () => windows) as Harness['matchAll']
  const openWindow = vi.fn(async () => null) as Harness['openWindow']
  const fetch = vi.fn(async () => undefined) as Harness['fetch']
  const self = {
    addEventListener: (type: string, fn: Listener) => {
      ;(listeners[type] ??= []).push(fn)
    },
    registration: { showNotification },
    clients: { matchAll, openWindow },
    location: { origin: ORIGIN, href: `${ORIGIN}/sw.js` },
  }
  const quietConsole = { log: vi.fn(), warn: vi.fn(), error: vi.fn(), info: vi.fn(), debug: vi.fn() }
  vm.runInNewContext(readFileSync(SW_PATH, 'utf8'), { self, URL, console: quietConsole, fetch })
  const fire = (type: string, event: Record<string, unknown>) => {
    let lifetime: Promise<unknown> | undefined
    const ev = {
      ...event,
      waitUntil: (p: Promise<unknown>) => {
        lifetime = p
      },
    }
    for (const l of listeners[type] ?? []) l(ev)
    return lifetime
  }
  return { listeners, showNotification, matchAll, openWindow, fetch, fire }
}

function pushData(text: string | null) {
  if (text === null) return null
  return {
    text: () => text,
    json: () => JSON.parse(text) as unknown,
  }
}

function click(h: Harness, data: unknown, action = '') {
  const close = vi.fn()
  const lifetime = h.fire('notificationclick', { action, notification: { data, close } })
  return { close, lifetime }
}

/** マイクロタスクを数回回して、待っている Promise が解決したかを観測する。 */
async function settled(p: Promise<unknown> | undefined): Promise<boolean> {
  let done = false
  void p?.then(
    () => {
      done = true
    },
    () => {
      done = true
    },
  )
  for (let i = 0; i < 20; i++) await Promise.resolve()
  return done
}

describe('CMP-261029-0620 sw-custom.js push', () => {
  it('AC-8 / AC-15: 見本 JSON の push で showNotification(title, {body, data}) が呼ばれる', async () => {
    const h = loadSw()
    await h.fire('push', { data: pushData(JSON.stringify(CONTRACT)) })
    expect(h.showNotification).toHaveBeenCalledTimes(1)
    const [title, options] = h.showNotification.mock.calls[0]!
    expect(title).toBe(CONTRACT.title)
    expect(options.body).toBe(CONTRACT.body)
    expect(options.data).toEqual(CONTRACT.data)
    expect((options.data as WebPushContract['data']).action_url).toBe('/teams/fc-u-18/schedules/10')
  })

  it.each([
    ['空（data なし）', null],
    ['JSON でない', 'not json {'],
    ['title 欠落', JSON.stringify({ body: 'b', data: { action_url: '/x' } })],
    ['JSON の null', 'null'],
    ['配列', '[]'],
  ])('AC-8: %s の payload でも既定の題で表示し、例外を投げない', async (_label, text) => {
    const h = loadSw()
    let lifetime: Promise<unknown> | undefined
    expect(() => {
      lifetime = h.fire('push', { data: pushData(text) })
    }).not.toThrow()
    expect(lifetime, 'waitUntil に表示の Promise を渡すこと').toBeDefined()
    await expect(lifetime).resolves.toBeUndefined()
    expect(h.showNotification).toHaveBeenCalledTimes(1)
    expect(h.showNotification.mock.calls[0]![0]).toBe(DEFAULT_TITLE)
  })
})

describe('CMP-261029-0620 sw-custom.js notificationclick', () => {
  const PATH = '/teams/fc-u-18/schedules/10'

  it('AC-9: 同一オリジンのタブがあれば focus と navigate(origin+path)、openWindow は呼ばない', async () => {
    const tab = windowClient(`${ORIGIN}/dashboard`)
    const h = loadSw([tab])
    const { close, lifetime } = click(h, { action_url: PATH })
    await lifetime
    expect(close).toHaveBeenCalled()
    expect(tab.focus).toHaveBeenCalled()
    expect(tab.navigate).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
    expect(h.openWindow).not.toHaveBeenCalled()
  })

  it('AC-9: タブが無ければ openWindow(origin+path)', async () => {
    const h = loadSw([])
    await click(h, { action_url: PATH }).lifetime
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
  })

  it.each([
    ['null', { action_url: null }],
    ['undefined', { action_url: undefined }],
    ['空文字', { action_url: '' }],
    ['数値', { action_url: 123 }],
    ['data 欠落', undefined],
    ['data が null', null],
  ])('AC-10: action_url が %s なら origin/ へ遷移', async (_label, data) => {
    const h = loadSw([])
    await click(h, data).lifetime
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}/`)
  })

  it.each([
    'https://evil.example/x',
    '//evil.example',
    '/\\evil.example',
    '/\t/evil.example',
    '/\n/evil.example',
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'http://app.example.com/x',
    'https://app.example.com.evil.example/x',
  ])('AC-11: %j は origin/ へ遷移し、別オリジンを開かない', async (url) => {
    const h = loadSw([])
    await click(h, { action_url: url }).lifetime
    expect(h.openWindow).toHaveBeenCalledTimes(1)
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}/`)
  })

  it('AC-11: 同一オリジンの絶対 URL は採用する', async () => {
    const h = loadSw([])
    await click(h, { action_url: `${ORIGIN}/teams/x?tab=1#a` }).lifetime
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}/teams/x?tab=1#a`)
  })

  it('AC-11: 別オリジンのタブ（オリジン名を含むだけのものも）は focus しない', async () => {
    const evil = windowClient('https://evil.example/?next=https://app.example.com')
    const lookalike = windowClient('https://app.example.com.evil.example/')
    const h = loadSw([evil, lookalike])
    await click(h, { action_url: PATH }).lifetime
    expect(evil.focus).not.toHaveBeenCalled()
    expect(evil.navigate).not.toHaveBeenCalled()
    expect(lookalike.focus).not.toHaveBeenCalled()
    expect(lookalike.navigate).not.toHaveBeenCalled()
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
  })

  it('AC-12: navigate が reject したら openWindow に切り替わる', async () => {
    const tab = windowClient(`${ORIGIN}/`)
    tab.navigate.mockRejectedValue(new TypeError('not controlled'))
    const h = loadSw([tab])
    await click(h, { action_url: PATH }).lifetime
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
  })

  it('AC-12: matchAll が reject しても openWindow が呼ばれる', async () => {
    const h = loadSw([])
    h.matchAll.mockRejectedValue(new Error('boom'))
    await click(h, { action_url: PATH }).lifetime
    expect(h.openWindow).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
  })

  it('AC-12: focus が reject しても navigate へ進む', async () => {
    const tab = windowClient(`${ORIGIN}/`)
    tab.focus.mockRejectedValue(new Error('no activation'))
    const h = loadSw([tab])
    await click(h, { action_url: PATH }).lifetime
    expect(tab.navigate).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
    expect(h.openWindow).not.toHaveBeenCalled()
  })

  it('AC-12: waitUntil の Promise は navigate が終わるまで解決しない', async () => {
    const tab = windowClient(`${ORIGIN}/`)
    let finish: (v: unknown) => void = () => undefined
    tab.navigate.mockImplementation(() => new Promise((r) => (finish = r)))
    const h = loadSw([tab])
    const { lifetime } = click(h, { action_url: PATH })
    expect(lifetime).toBeDefined()
    expect(await settled(lifetime)).toBe(false)
    expect(tab.navigate).toHaveBeenCalled()
    finish(tab)
    expect(await settled(lifetime)).toBe(true)
  })

  it('AC-12: waitUntil の Promise は openWindow が終わるまで解決しない', async () => {
    const h = loadSw([])
    let finish: (v: unknown) => void = () => undefined
    h.openWindow.mockImplementation(() => new Promise((r) => (finish = r)))
    const { lifetime } = click(h, { action_url: PATH })
    expect(await settled(lifetime)).toBe(false)
    expect(h.openWindow).toHaveBeenCalled()
    finish(null)
    expect(await settled(lifetime)).toBe(true)
  })

  it('AC-13: action=dismiss は遷移も openWindow もしない', async () => {
    const tab = windowClient(`${ORIGIN}/`)
    const h = loadSw([tab])
    const { close, lifetime } = click(h, { action_url: PATH }, 'dismiss')
    await lifetime
    expect(close).toHaveBeenCalled()
    expect(tab.navigate).not.toHaveBeenCalled()
    expect(tab.focus).not.toHaveBeenCalled()
    expect(h.openWindow).not.toHaveBeenCalled()
  })

  it('AC-13: action=open は本体タップと同じく遷移する', async () => {
    const tab = windowClient(`${ORIGIN}/`)
    const h = loadSw([tab])
    await click(h, { action_url: PATH }, 'open').lifetime
    expect(tab.navigate).toHaveBeenCalledWith(`${ORIGIN}${PATH}`)
  })
})

describe('CMP-261029-0620 sw-custom.js Background Sync', () => {
  it('AC-17: sw-custom.js を読み込んでも sync リスナーは登録されず、sync を送っても同期処理が起動しない', async () => {
    const h = loadSw([])
    expect(h.listeners.sync ?? []).toHaveLength(0)
    const lifetime = h.fire('sync', { tag: 'mannschaft-offline-sync' })
    expect(lifetime).toBeUndefined()
    expect(h.fetch).not.toHaveBeenCalled()
    expect(h.matchAll).not.toHaveBeenCalled()
  })
})
