// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readonly, ref, type Ref } from 'vue'
import type { ErrorReportState } from './useErrorReport'

const runtime = vi.hoisted(() => ({
  state: undefined as Ref<ErrorReportState> | undefined,
  auth: { accessToken: null as string | null, currentUser: null as { id: number } | null },
  config: () => ({ public: { apiBase: 'https://api.test' } }),
  useState: (_key: string, init: () => ErrorReportState) => (runtime.state ??= ref(init())),
}))
vi.mock('#app/nuxt', () => ({ useRuntimeConfig: runtime.config }))
vi.mock('#app/composables/state', () => ({ useState: runtime.useState }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => runtime.auth }))

// 実composableを動かし、外部送信境界だけで組み立て済みbodyを捕捉する。
const urls = [
  ['https://app.test/reset-password?token=QUERY#FRAGMENT', 'https://app.test/reset-password'],
  [
    'https://user:PASS@app.test/verify-email?unknown=QUERY#FRAGMENT',
    'https://app.test/verify-email',
  ],
  ['/verify-email?unknown=QUERY#FRAGMENT', '/verify-email'],
  ['/cmp-diagnostic/normal?next=/payment&token=QUERY#FRAGMENT', '/cmp-diagnostic/normal'],
  ['https://app.test', 'https://app.test/'],
  ['/a%3Fpart%23anchor?token=QUERY#FRAGMENT', '/a%3Fpart%23anchor'],
  ['/normal/42/%E6%97%A5', '/normal/42/%E6%97%A5'],
  ['//user:PASS@app.test/p?token=QUERY', '/invalid-url'],
  ['ftp://user:PASS@app.test/p?token=QUERY', '/invalid-url'],
  ['https:///missing-host?token=QUERY', '/invalid-url'],
  ['https://app.test/%zz?token=QUERY', '/invalid-url'],
  ['https://app.test/p\r\n?token=QUERY', '/invalid-url'],
  ['https://app.test\\p?token=QUERY', '/invalid-url'],
  ['?token=QUERY', '/invalid-url'],
  ['#FRAGMENT', '/invalid-url'],
  ['relative?token=QUERY', '/invalid-url'],
  ['', '/invalid-url'],
] as const

describe('診断URLの実送信境界', () => {
  const network = vi.fn(
    async (
      _url: string,
      _options: {
        headers: Record<string, string>
        body: Record<string, unknown>
      },
    ) => ({ data: { id: 1 } }),
  )
  let consoleError: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-16T03:00:00Z'))
    runtime.state = undefined
    runtime.auth.accessToken = null
    runtime.auth.currentUser = null
    network.mockClear()
    vi.stubGlobal('useState', runtime.useState)
    vi.stubGlobal('useRuntimeConfig', runtime.config)
    vi.stubGlobal('useAuthStore', () => runtime.auth)
    vi.stubGlobal('readonly', readonly)
    vi.stubGlobal('$fetch', network)
    vi.stubGlobal('window', {
      location: { href: 'https://app.test/reset-password?token=QUERY#FRAGMENT' },
    })
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it.each(urls)('captureのstateとbodyで %s を安全化する', async (input, expected) => {
    vi.stubGlobal('window', { location: { href: input } })
    const report = (await import('./useErrorReport')).useErrorReport()
    report.capture(new Error('合成エラー'))
    expect(network).toHaveBeenCalledTimes(1)
    expect(network.mock.calls[0]?.[1].body.pageUrl).toBe(expected)
    expect(report.state.value.pageUrl).toBe(expected)
    expect(window.location.href).toBe(input)
    expect(network.mock.calls[0]?.[1].headers).toEqual({})
    await Promise.resolve()
  })

  it.each(urls)('quietのbodyで %s を安全化する', async (input, expected) => {
    vi.stubGlobal('window', { location: { href: input } })
    const report = (await import('./useErrorReport')).useErrorReport()
    report.captureQuiet(new Error('合成404'), { context: 'label-save' })
    expect(network).toHaveBeenCalledTimes(1)
    expect(network.mock.calls[0]?.[1].body.pageUrl).toBe(expected)
    expect(report.state.value.visible).toBe(false)
    await Promise.resolve()
  })

  it('古い共有stateのコメント再送も送信直前に除去し認証headerを維持する', async () => {
    const report = (await import('./useErrorReport')).useErrorReport()
    runtime.auth.accessToken = 'SYNTHETIC_LOGIN'
    runtime.auth.currentUser = { id: 42 }
    runtime.state!.value.pageUrl = 'https://user:PASS@app.test/p?secret=QUERY#FRAGMENT'
    runtime.state!.value.errorMessage = '合成エラー'
    await report.submitComment('追加情報')
    expect(network.mock.calls[0]?.[1]).toMatchObject({
      headers: { Authorization: 'Bearer SYNTHETIC_LOGIN' },
      body: { pageUrl: 'https://app.test/p', userComment: '追加情報', userId: 42 },
    })
    expect(report.state.value.pageUrl).toBe('https://app.test/p')
    expect(report.state.value.commentSent).toBe(true)
  })

  it('確定API URLの連結と通常・cooldownのconsole metadataを除去しcallerを変えない', async () => {
    const report = (await import('./useErrorReport')).useErrorReport()
    const apiUrl = 'https://user:PASS@api.test/auth/oauth/link/confirm?token=QUERY#FRAGMENT'
    const meta = Object.freeze({
      apiUrl,
      statusCode: 500,
      context: 'oauth-confirm',
      requestId: 'synthetic-id',
    })
    report.capture(new Error('確認失敗'), meta)
    await Promise.resolve()
    expect(network.mock.calls[0]?.[1].body.errorMessage).toBe(
      '[500] https://api.test/auth/oauth/link/confirm: 確認失敗',
    )
    expect(report.state.value.errorMessage).not.toContain('QUERY')
    expect(consoleError.mock.calls[0]?.[2]).toEqual({
      ...meta,
      apiUrl: 'https://api.test/auth/oauth/link/confirm',
    })
    report.close()
    report.capture(new Error('再確認失敗'), meta)
    expect(consoleError).toHaveBeenCalledTimes(2)
    expect(consoleError.mock.calls[1]?.[2]).toEqual({
      ...meta,
      apiUrl: 'https://api.test/auth/oauth/link/confirm',
    })
    expect(network).toHaveBeenCalledTimes(1)
    expect(meta.apiUrl).toBe(apiUrl)
  })

  it.each([undefined, {}, { apiUrl: '' }])(
    '無指定・空metaのconsole引数を維持する %j',
    async (meta) => {
      const report = (await import('./useErrorReport')).useErrorReport()
      report.capture(new Error('合成エラー'), meta)
      expect(consoleError.mock.calls[0]?.[2]).toEqual(meta)
      await Promise.resolve()
    },
  )
})
