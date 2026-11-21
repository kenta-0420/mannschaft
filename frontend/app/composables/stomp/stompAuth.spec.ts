// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { createStompAuthRecovery } from './stompAuth'

const h = vi.hoisted(() => ({ refresh: vi.fn(), logout: vi.fn() }))
vi.mock('~/composables/useApi', () => ({
  performTokenRefresh: (...args: unknown[]) => h.refresh(...args),
  handleAuthFailureLogout: (...args: unknown[]) => h.logout(...args),
}))

function fixture() {
  h.refresh.mockReset().mockResolvedValue('refreshed')
  h.logout.mockReset().mockResolvedValue(undefined)
  const store = reactive({
    user: { id: 7 } as { id: number } | null,
    get isAuthenticated() {
      return this.user !== null
    },
  })
  type Deps = Parameters<typeof createStompAuthRecovery>[0]
  const recovery = createStompAuthRecovery({
    config: { public: { apiBase: '' } } as unknown as Deps['config'],
    authStore: store as unknown as Deps['authStore'],
    now: () => 1000,
  })
  return { store, recovery }
}

describe('N2: STOMP回復のセッション境界', () => {
  it('同user同世代は更新直後の30秒窓を維持する', async () => {
    const { recovery } = fixture()
    expect(await recovery.recover()).toBe('RETRY')
    expect(await recovery.recover()).toBe('EXHAUSTED')
    expect(h.refresh).toHaveBeenCalledTimes(1)
  })

  it('同一factoryでも別userへAの更新窓を持ち越さない', async () => {
    const { store, recovery } = fixture()
    expect(await recovery.recover()).toBe('RETRY')
    store.user = { id: 8 }
    expect(await recovery.recover()).toBe('RETRY')
    expect(h.refresh).toHaveBeenCalledTimes(2)
  })

  it('同idの再loginも別世代として更新窓を分離する', async () => {
    const { store, recovery } = fixture()
    await recovery.recover()
    store.user = null
    store.user = { id: 7 }
    expect(await recovery.recover()).toBe('RETRY')
  })

  it('Aの遅着auth_failedでBをlogoutしない', async () => {
    const { store, recovery } = fixture()
    let complete!: (value: string) => void
    h.refresh.mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          complete = resolve
        }),
    )
    const old = recovery.recover()
    store.user = { id: 8 }
    complete('auth_failed')
    expect(await old).toBe('SESSION_CHANGED')
    expect(h.logout).not.toHaveBeenCalled()
  })

  it('Aの遅着TRANSIENTはBの新しい更新窓を消去しない', async () => {
    const { store, recovery } = fixture()
    let complete!: (value: string) => void
    h.refresh.mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          complete = resolve
        }),
    )
    const old = recovery.recover()
    store.user = { id: 8 }
    expect(await recovery.recover()).toBe('RETRY')
    complete('transient')
    expect(await old).toBe('SESSION_CHANGED')
    expect(await recovery.recover()).toBe('EXHAUSTED')
    expect(h.refresh).toHaveBeenCalledTimes(2)
  })
})
