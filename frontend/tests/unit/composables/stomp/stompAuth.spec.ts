import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * CMP-261104-0337 STOMP 認証の共通補助（stompAuth）のユニットテスト。
 *
 * 観点:
 *   WSAUTH-001: トークンが無い（null・空・空白）ときは Authorization ヘッダを付けない（空 Bearer 禁止）
 *   WSAUTH-002: トークンがあれば `Bearer <token>` を付ける
 *   WSAUTH-003: x-error-code を 3 コード＋未知（UNKNOWN）に読み分ける
 *   WSAUTH-004: 未ログイン（匿名）の UNAUTHENTICATED は更新もログアウトもしない（ANONYMOUS）
 *   WSAUTH-005: 更新成功で RETRY、更新が 401 ならログアウトして LOGGED_OUT、通信断は TRANSIENT
 *   WSAUTH-006: 複数接続から同時に UNAUTHENTICATED が来ても refresh API は 1 回だけ（single-flight）
 *   WSAUTH-007: 更新直後（窓内）の再拒否は更新を繰り返さず EXHAUSTED
 *   WSAUTH-008: handleStompErrorFrame — FORBIDDEN/INVALID_DESTINATION は onDenied、未知は何もしない
 */

const mockOfetch = vi.fn()
vi.mock('ofetch', () => ({
  ofetch: (...args: unknown[]) => mockOfetch(...args),
}))
vi.mock('~/composables/useApiBaseUrl', () => ({
  resolveApiBaseUrl: () => 'http://localhost:8080',
}))

const {
  buildStompConnectHeaders,
  readStompErrorCode,
  createStompAuthRecovery,
  handleStompErrorFrame,
  STOMP_AUTH_RETRY_WINDOW_MS,
} = await import('~/composables/stomp/stompAuth')

type Deps = Parameters<typeof createStompAuthRecovery>[0]

interface FakeAuthStore {
  isAuthenticated: boolean
  user: { id: number } | null
  accessToken: string | null
  setTokens: ReturnType<typeof vi.fn>
  logout: ReturnType<typeof vi.fn>
}

function makeAuthStore(authenticated = true): FakeAuthStore {
  return {
    isAuthenticated: authenticated,
    user: authenticated ? { id: 7 } : null,
    accessToken: null,
    setTokens: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function deps(store: FakeAuthStore, now?: () => number): Deps {
  return {
    config: { public: { apiBase: '' } } as unknown as Deps['config'],
    authStore: store as unknown as Deps['authStore'],
    now,
  }
}

function fetchError(status: number): Error {
  const err = new Error(`HTTP ${status}`)
  ;(err as { response?: { status: number; headers: Headers } }).response = { status, headers: new Headers() }
  return err
}

beforeEach(() => {
  mockOfetch.mockReset()
})

describe('buildStompConnectHeaders — 空 Bearer 禁止', () => {
  it('WSAUTH-001: トークンが無いときは Authorization を付けない', () => {
    expect(buildStompConnectHeaders(null)).toEqual({})
    expect(buildStompConnectHeaders(undefined)).toEqual({})
    expect(buildStompConnectHeaders('')).toEqual({})
    expect(buildStompConnectHeaders('   ')).toEqual({})
  })

  it('WSAUTH-002: トークンがあれば Bearer を付ける', () => {
    expect(buildStompConnectHeaders('abc.def')).toEqual({ Authorization: 'Bearer abc.def' })
  })
})

describe('readStompErrorCode — x-error-code の読み分け', () => {
  it('WSAUTH-003: 3 コードと未知を区別する', () => {
    expect(readStompErrorCode({ headers: { 'x-error-code': 'UNAUTHENTICATED' } })).toBe('UNAUTHENTICATED')
    expect(readStompErrorCode({ headers: { 'x-error-code': 'FORBIDDEN' } })).toBe('FORBIDDEN')
    expect(readStompErrorCode({ headers: { 'x-error-code': 'INVALID_DESTINATION' } })).toBe('INVALID_DESTINATION')
    expect(readStompErrorCode({ headers: { 'x-error-code': 'SOMETHING_NEW' } })).toBe('UNKNOWN')
    expect(readStompErrorCode({ headers: { message: 'boom' } })).toBe('UNKNOWN')
  })
})

describe('createStompAuthRecovery — UNAUTHENTICATED の回復', () => {
  it('WSAUTH-004: 匿名（未ログイン）は更新もログアウトもしない', async () => {
    const store = makeAuthStore(false)
    const outcome = await createStompAuthRecovery(deps(store)).recover()
    expect(outcome).toBe('ANONYMOUS')
    expect(mockOfetch).not.toHaveBeenCalled()
    expect(store.logout).not.toHaveBeenCalled()
  })

  it('WSAUTH-005a: 更新成功で RETRY（新トークンを保存）', async () => {
    mockOfetch.mockResolvedValueOnce({ data: { accessToken: 'new-a', refreshToken: 'new-r' } })
    const store = makeAuthStore()
    const outcome = await createStompAuthRecovery(deps(store)).recover()
    expect(outcome).toBe('RETRY')
    expect(store.setTokens).toHaveBeenCalledWith('new-a', 'new-r')
    expect(store.logout).not.toHaveBeenCalled()
  })

  it('WSAUTH-005b: 更新が 401 ならログアウトして LOGGED_OUT', async () => {
    mockOfetch.mockRejectedValueOnce(fetchError(401))
    const store = makeAuthStore()
    const outcome = await createStompAuthRecovery(deps(store)).recover()
    expect(outcome).toBe('LOGGED_OUT')
    expect(store.logout).toHaveBeenCalledTimes(1)
    expect(store.logout).toHaveBeenCalledWith({ reason: 'session_expired' })
  })

  it('WSAUTH-005c: 更新が通信断（5xx）なら TRANSIENT でログアウトしない', async () => {
    mockOfetch.mockRejectedValueOnce(fetchError(503))
    const store = makeAuthStore()
    const outcome = await createStompAuthRecovery(deps(store)).recover()
    expect(outcome).toBe('TRANSIENT')
    expect(store.logout).not.toHaveBeenCalled()
  })

  it('WSAUTH-006: 複数接続から同時に UNAUTHENTICATED が来ても refresh は 1 回に集約される', async () => {
    let release: ((v: unknown) => void) | undefined
    mockOfetch.mockImplementationOnce(() => new Promise((resolve) => { release = resolve }))
    const store = makeAuthStore()
    // 接続ごとに別の回復手順（chat / match / corkboard / emergency が各自で持つ）を作る。
    const recoveries = [1, 2, 3, 4].map(() => createStompAuthRecovery(deps(store)))
    const pending = recoveries.map(r => r.recover())
    release?.({ data: { accessToken: 'new-a', refreshToken: 'new-r' } })
    const outcomes = await Promise.all(pending)
    expect(outcomes).toEqual(['RETRY', 'RETRY', 'RETRY', 'RETRY'])
    expect(mockOfetch).toHaveBeenCalledTimes(1)
    expect(store.setTokens).toHaveBeenCalledTimes(1)
  })

  it('WSAUTH-007: 更新直後の再拒否は更新を繰り返さず EXHAUSTED（窓を過ぎれば再び更新する）', async () => {
    mockOfetch.mockResolvedValue({ data: { accessToken: 'new-a', refreshToken: 'new-r' } })
    let clock = 1_000_000
    const store = makeAuthStore()
    const recovery = createStompAuthRecovery(deps(store, () => clock))
    expect(await recovery.recover()).toBe('RETRY')
    clock += 1_000
    expect(await recovery.recover()).toBe('EXHAUSTED')
    expect(mockOfetch).toHaveBeenCalledTimes(1)
    expect(store.logout).not.toHaveBeenCalled()
    clock += STOMP_AUTH_RETRY_WINDOW_MS
    expect(await recovery.recover()).toBe('RETRY')
    expect(mockOfetch).toHaveBeenCalledTimes(2)
  })
})

describe('handleStompErrorFrame — 専用接続の ERROR 振り分け', () => {
  function handlers() {
    return { onDenied: vi.fn(), onUnauthenticated: vi.fn() }
  }

  it('WSAUTH-008a: FORBIDDEN / INVALID_DESTINATION は onDenied（回復しない）', () => {
    const recovery = { recover: vi.fn() }
    for (const code of ['FORBIDDEN', 'INVALID_DESTINATION']) {
      const h = handlers()
      const result = handleStompErrorFrame({ headers: { 'x-error-code': code } }, recovery, h)
      expect(result).toBeNull()
      expect(h.onDenied).toHaveBeenCalledWith(code)
    }
    expect(recovery.recover).not.toHaveBeenCalled()
  })

  it('WSAUTH-008b: 未知のコードは何もしない（従来の自動再接続に任せる）', () => {
    const recovery = { recover: vi.fn() }
    const h = handlers()
    expect(handleStompErrorFrame({ headers: { message: 'x' } }, recovery, h)).toBeNull()
    expect(h.onDenied).not.toHaveBeenCalled()
    expect(h.onUnauthenticated).not.toHaveBeenCalled()
    expect(recovery.recover).not.toHaveBeenCalled()
  })

  it('WSAUTH-008c: UNAUTHENTICATED は回復を待ち、RETRY なら止めない／ANONYMOUS なら onUnauthenticated', async () => {
    const retry = { recover: vi.fn().mockResolvedValue('RETRY') }
    const h1 = handlers()
    await handleStompErrorFrame({ headers: { 'x-error-code': 'UNAUTHENTICATED' } }, retry, h1)
    expect(h1.onUnauthenticated).not.toHaveBeenCalled()

    const anon = { recover: vi.fn().mockResolvedValue('ANONYMOUS') }
    const h2 = handlers()
    await handleStompErrorFrame({ headers: { 'x-error-code': 'UNAUTHENTICATED' } }, anon, h2)
    expect(h2.onUnauthenticated).toHaveBeenCalledWith('ANONYMOUS')
  })
})
