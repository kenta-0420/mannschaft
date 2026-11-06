import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * useApi の 429 共通トースト「操作が集中しています」の適用条件（CMP-260827-1808 V1 / #2517）。
 *
 * 基盤のレート制限（AbstractRateLimitFilter 等）は error.code を持たない文字列本文
 * {"error":"Too many requests"} + Retry-After を返す。一方、業務固有の 429（VILLAGE_012 など）は
 * error.code を持ち、呼び出し側が固有のエラー文を出す。後者にまで共通トーストを重ねると
 * 1 操作で 2 枚（連打で N 倍）のトーストが出るため、共通トーストは「コード無しの 429」に限る。
 */

type HookOptions = Record<string, unknown>
type Hooks = {
  onRequest: (ctx: { options: HookOptions }) => void
  onResponseError: (ctx: { options: HookOptions, request: string, response: unknown }) => Promise<void>
}

const { captured } = vi.hoisted(() => ({ captured: { hooks: null as unknown } }))

vi.mock('ofetch', () => {
  const fn = Object.assign(vi.fn(), {
    create: (opts: unknown) => {
      captured.hooks = opts
      return vi.fn()
    },
  })
  return { ofetch: fn }
})

const { useApi } = await import('~/composables/useApi')

function fire(data: unknown, retryAfter?: string) {
  const toast = { add: vi.fn() }
  const tMock = (key: string, named?: Record<string, unknown>) => (named ? `${key}:${JSON.stringify(named)}` : key)
  const nuxtApp = useNuxtApp() as unknown as Record<string, { add?: unknown, t?: unknown } | undefined>
  // $i18n は nuxt-i18n が非 configurable で定義するため defineProperty できない。
  // 既存なら中身のメソッドだけ差し替え、無ければ（$toast はクライアントプラグイン由来）生やす。
  const stub = (key: '$toast' | '$i18n', member: 'add' | 't', impl: unknown) => {
    const existing = nuxtApp[key]
    if (existing) {
      existing[member] = impl
      return
    }
    Object.defineProperty(nuxtApp, key, { value: { [member]: impl }, configurable: true, writable: true })
  }
  stub('$toast', 'add', toast.add)
  stub('$i18n', 't', tMock)
  useApi()
  const hooks = captured.hooks as Hooks
  const options: HookOptions = {}
  hooks.onRequest({ options })
  const headers = new Headers(retryAfter ? { 'Retry-After': retryAfter } : {})
  const response = { status: 429, statusText: 'Too Many Requests', headers, _data: data }
  return { toast, run: () => hooks.onResponseError({ options, request: '/api/v1/x', response }) }
}

describe('useApi 429 共通トースト', () => {
  beforeEach(() => {
    captured.hooks = null
  })

  it('業務固有コード付き 429（VILLAGE_012）では共通トーストを出さない', async () => {
    const { toast, run } = fire({ error: { code: 'VILLAGE_012', message: '参加できる村の上限です', severity: 'WARN' } })
    await run()
    expect(toast.add).not.toHaveBeenCalled()
  })

  it('基盤のレート制限（文字列本文）の 429 では従来どおり共通トーストを出す', async () => {
    const { toast, run } = fire({ error: 'Too many requests' })
    await run()
    expect(toast.add).toHaveBeenCalledTimes(1)
    expect(toast.add.mock.calls[0]![0]).toMatchObject({ severity: 'warn', summary: 'error.rate_limited' })
  })

  it('本文が無い 429（WeatherController 等）でも共通トーストを出す', async () => {
    const { toast, run } = fire(undefined)
    await run()
    expect(toast.add).toHaveBeenCalledTimes(1)
  })

  it('Retry-After の秒数表示が維持される', async () => {
    const { toast, run } = fire({ error: 'Too many requests' }, '30')
    await run()
    expect(toast.add.mock.calls[0]![0]).toMatchObject({
      detail: expect.stringContaining('"seconds":30'),
    })
  })
})
