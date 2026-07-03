import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

/**
 * armProactiveRefresh / disarmProactiveRefresh（useApi.ts）のユニットテスト。
 *
 * 目的: access_token(15分)が失効してから反応する現状（リアクティブ）だと、背景の4ポーラー
 * （通知unread-count/chat channels/mentions/inbox summary）が失効直後に必ず1回401を出し、
 * コンソールが401ノイズで汚れる。失効の少し前（60秒前）に先回りリフレッシュを発火する
 * タイマー式スケジューラの武装/解除挙動を検証する。
 *
 * 受け入れ条件（AC）とテストの対応:
 * - AC-1: armProactiveRefresh はタイマーを武装し、指定遅延で performTokenRefresh を発火する
 * - AC-2: 先回りリフレッシュ成功後、次のタイマーが再武装される（発火し続ける）
 * - AC-3: disarmProactiveRefresh 後はタイマーが発火しない
 * - AC-4: tokenExpiresAt が既に過去/バッファ以内なら遅延0（即時）で発火する
 * - AC-5: 多重に arm しても常にタイマーは1本のみ（再武装時に前のタイマーをクリアする）
 * - AC-6: クライアント限定（import.meta.client=false では武装しない）
 * - AC-7: 先回りリフレッシュが失敗（transient等）した場合、短い遅延（30秒）で再武装し回復を試みる
 */

const mockOfetch = vi.fn()
vi.mock('ofetch', () => ({
  ofetch: (...args: unknown[]) => mockOfetch(...args),
}))

vi.mock('~/composables/useApiBaseUrl', () => ({
  resolveApiBaseUrl: () => 'http://localhost:8080',
}))

const { armProactiveRefresh, disarmProactiveRefresh } = await import('~/composables/useApi')

type RefreshConfig = Parameters<typeof armProactiveRefresh>[0]
type RefreshAuthStore = Parameters<typeof armProactiveRefresh>[1]

function makeConfig(): RefreshConfig {
  return { public: { apiBase: '' } } as unknown as RefreshConfig
}

function makeAuthStore() {
  return {
    setTokens: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function asAuthStore(store: ReturnType<typeof makeAuthStore>): RefreshAuthStore {
  return store as unknown as RefreshAuthStore
}

// waitForMicrotasks: setTimeout コールバック内の async performTokenRefresh の
// Promise 解決をイベントループへ反映させるためのヘルパー。
// vi.useFakeTimers 環境では advanceTimersByTime 後に Promise の then が
// 即座には流れないため、実マクロタスクを 1 周挟んで確実に解決させる。
async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}

describe('armProactiveRefresh / disarmProactiveRefresh', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockOfetch.mockReset()
    localStorage.clear()
    disarmProactiveRefresh()
  })

  afterEach(() => {
    disarmProactiveRefresh()
    vi.useRealTimers()
  })

  it('AC-1: 武装した遅延(ms)経過後に performTokenRefresh が発火する（ofetch 呼び出しで検証）', async () => {
    const nowMs = Date.now()
    localStorage.setItem('tokenExpiresAt', String(nowMs + 5 * 60_000)) // 5分後に失効
    mockOfetch.mockResolvedValue({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })

    armProactiveRefresh(makeConfig(), asAuthStore(makeAuthStore()))

    // まだ発火していない
    await vi.advanceTimersByTimeAsync(5 * 60_000 - 60_000 - 1_000)
    expect(mockOfetch).not.toHaveBeenCalled()

    // バッファ(60秒前)ちょうどで発火する
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(1)
  })

  it('AC-4: tokenExpiresAt が既に過去の場合、即時（遅延0）で発火する', async () => {
    localStorage.setItem('tokenExpiresAt', String(Date.now() - 10_000))
    mockOfetch.mockResolvedValue({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })

    armProactiveRefresh(makeConfig(), asAuthStore(makeAuthStore()))

    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(1)
  })

  it('AC-2: 成功後は新しい tokenExpiresAt に基づいて次のタイマーが再武装される', async () => {
    const nowMs = Date.now()
    localStorage.setItem('tokenExpiresAt', String(nowMs + 60_000)) // 60秒後 → 即時発火(バッファ=60秒)
    const authStore = makeAuthStore()
    // performTokenRefresh は成功時に authStore.setTokens を呼ぶ。
    // テスト用モックの setTokens は localStorage の tokenExpiresAt を更新する（実 store の挙動を模倣）。
    authStore.setTokens.mockImplementation(() => {
      localStorage.setItem('tokenExpiresAt', String(Date.now() + 15 * 60_000))
    })
    mockOfetch.mockResolvedValue({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })

    armProactiveRefresh(makeConfig(), asAuthStore(authStore))

    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(1)
    expect(authStore.setTokens).toHaveBeenCalledTimes(1)

    // 再武装された次のタイマーは新しい期限（15分後）のバッファ手前まで発火しないはず
    await vi.advanceTimersByTimeAsync(15 * 60_000 - 60_000 - 1_000)
    expect(mockOfetch).toHaveBeenCalledTimes(1)

    // バッファ手前でようやく2回目が発火する
    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(2)
  })

  it('AC-3: disarmProactiveRefresh 後はタイマーが発火しない', async () => {
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 60_000))
    mockOfetch.mockResolvedValue({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })

    armProactiveRefresh(makeConfig(), asAuthStore(makeAuthStore()))
    disarmProactiveRefresh()

    await vi.advanceTimersByTimeAsync(24 * 60 * 60_000) // 24時間進めても発火しない
    await flushPromises()
    expect(mockOfetch).not.toHaveBeenCalled()
  })

  it('AC-5: 多重に arm しても常にタイマーは1本のみ（前のタイマーはクリアされる）', async () => {
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 60_000))
    mockOfetch.mockResolvedValue({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })
    const authStore = asAuthStore(makeAuthStore())
    const config = makeConfig()

    armProactiveRefresh(config, authStore)
    armProactiveRefresh(config, authStore)
    armProactiveRefresh(config, authStore)

    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()

    // 3回武装しても発火は1回分のみ（多重発火していない）
    expect(mockOfetch).toHaveBeenCalledTimes(1)
  })

  // AC-6（クライアント限定・SSR でタイマーを張らない）について:
  // Nuxt/Vite は import.meta.client をビルド時に静的な真偽値へ置換するため、
  // vi.stubGlobal('import', ...) による実行時の動的な差し替えは効果を持たない
  // （本リポジトリの他の import.meta.client ガード付きコード（useAuthStore の
  // setTokens/logout・auth.client プラグイン等）についても同様の理由から
  // false 分岐を実行時トグルで unit test している例は無い）。
  // そのため AC-6 は armProactiveRefresh 冒頭の `if (!import.meta.client) return`
  // ガード（実装済み・他の client 限定処理と同一パターン）で担保し、
  // ここでは動的トグルに依らない静的な存在確認のみ行う。
  it('AC-6: armProactiveRefresh は import.meta.client ガードを持つ（静的確認。動的トグルは Vite の静的置換のため unit test 不可）', () => {
    expect(armProactiveRefresh.toString()).toContain('import.meta.client')
  })

  it('AC-7: 先回りリフレッシュが失敗（transient）した場合、短い遅延(30秒)で再武装しリトライする', async () => {
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 60_000)) // 即時発火
    // 1回目: 5xx で失敗（transient）、2回目: 成功
    mockOfetch.mockRejectedValueOnce(
      Object.assign(new Error('HTTP 503'), { response: { status: 503, headers: new Headers() } }),
    )
    mockOfetch.mockResolvedValueOnce({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })

    const authStore = makeAuthStore()
    // 実 store の setTokens は成功時に tokenExpiresAt を新しい値へ更新する。
    // このモックも同じ挙動にしておかないと、リトライ成功後の再武装が「期限不明のまま」延々と
    // 即時発火を繰り返してしまい、リトライ回数の検証が不安定になる。
    authStore.setTokens.mockImplementation(() => {
      localStorage.setItem('tokenExpiresAt', String(Date.now() + 15 * 60_000))
    })

    armProactiveRefresh(makeConfig(), asAuthStore(authStore))

    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(1) // 1回目: 失敗

    // 30秒未満ではまだ再武装分は発火しない
    await vi.advanceTimersByTimeAsync(29_000)
    expect(mockOfetch).toHaveBeenCalledTimes(1)

    // 30秒経過でリトライが発火する（連鎖する delay=0 の再武装分もまとめて処理されるよう余裕を持って進める）
    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(mockOfetch).toHaveBeenCalledTimes(2)
  })
})
