import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * F03.12 Phase10 §16 DismissalDialog.vue のユニットテスト。
 *
 * - notifyGuardians がデフォルト true で初期描画される
 * - 送信ボタンを押したとき useDismissal().submit が正しいペイロードで呼ばれる
 *
 * <p>Dialog は Teleport で document.body にレンダリングされるため、document.body を直接走査する。</p>
 */

// useDismissal を完全にモック化
const mockSubmit = vi.fn()
const mockLoading = { value: false }
vi.mock('~/composables/useDismissal', () => ({
  useDismissal: () => ({
    status: { value: null },
    loading: mockLoading,
    error: { value: null },
    loadStatus: vi.fn(),
    send: mockSubmit,
    submit: mockSubmit,
  }),
}))

// useDismissalApi も念のためダミー化（未使用のはずだが auto-import のため）
vi.mock('~/composables/useDismissalApi', () => ({
  useDismissalApi: () => ({
    submitDismissal: vi.fn(),
    getDismissalStatus: vi.fn(),
  }),
}))

const DismissalDialog = (await import('~/components/event/dismissal/DismissalDialog.vue')).default

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

beforeEach(() => {
  mockSubmit.mockReset()
  mockSubmit.mockResolvedValue(null)
  mockLoading.value = false
})

afterEach(() => {
  // Teleport された DOM のクリーンアップ
  document.body.querySelectorAll('.p-dialog').forEach((el) => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach((el) => el.remove())
})

describe('DismissalDialog.vue', () => {
  it('open=true で開いたとき notifyGuardians がデフォルト true', async () => {
    const wrapper = await mountSuspended(DismissalDialog, {
      props: { teamId: 1, eventId: 2, open: true },
    })

    // PrimeVue ToggleSwitch は内部に input[role="switch"] or checkbox を持つ
    const sw = findByTestId<HTMLElement>('dismissal-notify-guardians')
    expect(sw).not.toBeNull()
    // ToggleSwitch のラッパー要素 or 内部 input の checked / aria-checked を確認
    const input = sw?.querySelector<HTMLInputElement>('input')
    if (input) {
      expect(input.checked).toBe(true)
    } else {
      // PrimeVue 実装が変わって aria 属性で表現する場合のフォールバック
      const ariaChecked =
        sw?.getAttribute('aria-checked') ?? sw?.querySelector('[aria-checked]')?.getAttribute('aria-checked')
      expect(ariaChecked).toBe('true')
    }
    void wrapper
  })

  it('送信ボタンクリックで submit に notifyGuardians=true 含むペイロードが渡る', async () => {
    const wrapper = await mountSuspended(DismissalDialog, {
      props: { teamId: 10, eventId: 20, open: true, defaultMessage: 'お疲れさまでした' },
    })

    const btn = findByTestId<HTMLButtonElement>('dismissal-submit')
    expect(btn).not.toBeNull()
    btn!.click()

    // 非同期 emit/submit の完了を待つ
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    expect(mockSubmit).toHaveBeenCalledTimes(1)
    const arg = mockSubmit.mock.calls[0]?.[0] as Record<string, unknown> | undefined
    expect(arg).toBeDefined()
    expect(arg?.message).toBe('お疲れさまでした')
    expect(arg?.notifyGuardians).toBe(true)
    // actualEndAt は ISO 文字列
    expect(typeof arg?.actualEndAt).toBe('string')
    expect((arg?.actualEndAt as string).length).toBeGreaterThan(0)
  })

  it('defaultMessage 未指定時は message 省略（undefined）で送信', async () => {
    const wrapper = await mountSuspended(DismissalDialog, {
      props: { teamId: 10, eventId: 20, open: true },
    })

    const btn = findByTestId<HTMLButtonElement>('dismissal-submit')
    btn!.click()
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    expect(mockSubmit).toHaveBeenCalledTimes(1)
    const arg = mockSubmit.mock.calls[0]?.[0] as Record<string, unknown> | undefined
    // 空文字なら undefined にしている
    expect(arg?.message).toBeUndefined()
    expect(arg?.notifyGuardians).toBe(true)
  })
})
