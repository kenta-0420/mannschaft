import { beforeEach, describe, expect, it, vi } from 'vitest'

const onBeforeMount = vi.fn()
const onMounted = vi.fn()

vi.mock('vue', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue')>()

  return {
    ...actual,
    onBeforeMount,
    onMounted,
  }
})

const { useHydrated } = await import('../../../app/composables/useHydrated')

describe('useHydrated', () => {
  beforeEach(() => {
    onBeforeMount.mockReset()
    onMounted.mockReset()
  })

  it('SSR中はfalseを維持し、ハイドレーションの同期パッチ前にtrueへ切り替える', () => {
    const hydrated = useHydrated()

    expect(hydrated.value).toBe(false)
    expect(onMounted).not.toHaveBeenCalled()
    expect(onBeforeMount).toHaveBeenCalledOnce()

    const markHydrated = onBeforeMount.mock.calls[0]?.[0]
    if (typeof markHydrated !== 'function') {
      throw new Error('onBeforeMount のコールバックが登録されていません')
    }
    markHydrated()

    expect(hydrated.value).toBe(true)
  })
})
