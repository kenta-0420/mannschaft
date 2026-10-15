import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RollCallSubmitDialog from '~/components/event/rollCall/RollCallSubmitDialog.vue'
import type { RollCallEntry } from '~/types/care'

/**
 * F03.12 §14 RollCallSubmitDialog.vue のユニットテスト。
 *
 * <h3>カバー範囲</h3>
 * <ul>
 *   <li>PRESENT/LATE/ABSENT の人数集計が正しく表示される</li>
 *   <li>guardianSetupWarnings がある場合に警告 UI が表示される</li>
 *   <li>確定ボタンで confirm が emit される / キャンセルで cancel + 閉じが emit される</li>
 * </ul>
 *
 * <p>PrimeVue の {@code Dialog} は {@code <Teleport to="body">} で
 * document.body 直下にレンダリングされるため、本体は
 * {@code document.body.querySelector} で取得する。</p>
 */

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}
function getByTestId<T extends Element = HTMLElement>(testId: string): T {
  const el = findByTestId<T>(testId)
  if (!el) throw new Error(`[data-testid="${testId}"] not found`)
  return el
}

afterEach(() => {
  // Teleport された DOM の後始末
  const dialog = findByTestId('roll-call-submit-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

describe('RollCallSubmitDialog.vue', () => {
  const entries: RollCallEntry[] = [
    { userId: 1, status: 'PRESENT' },
    { userId: 2, status: 'PRESENT' },
    { userId: 3, status: 'LATE', lateArrivalMinutes: 10 },
    { userId: 4, status: 'ABSENT', absenceReason: 'SICK' },
    { userId: 5, status: 'ABSENT', absenceReason: 'NOT_ARRIVED' },
  ]

  it('内訳カウントが正しく表示される（PRESENT=2, LATE=1, ABSENT=2）', async () => {
    await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries,
        notifyImmediately: true,
        guardianSetupWarnings: [],
      },
    })
    expect(getByTestId('roll-call-present-count').textContent?.trim()).toBe('2')
    expect(getByTestId('roll-call-late-count').textContent?.trim()).toBe('1')
    expect(getByTestId('roll-call-absent-count').textContent?.trim()).toBe('2')
  })

  it('空の entries でも 0 を表示する', async () => {
    await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries: [],
        notifyImmediately: false,
        guardianSetupWarnings: [],
      },
    })
    expect(getByTestId('roll-call-present-count').textContent?.trim()).toBe('0')
    expect(getByTestId('roll-call-late-count').textContent?.trim()).toBe('0')
    expect(getByTestId('roll-call-absent-count').textContent?.trim()).toBe('0')
  })

  it('guardianSetupWarnings がある場合に警告ブロックが描画される', async () => {
    await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries,
        notifyImmediately: true,
        guardianSetupWarnings: ['鈴木一郎', '佐藤花子'],
      },
    })
    const warn = getByTestId('roll-call-warnings')
    const items = warn.querySelectorAll('li')
    expect(items.length).toBe(2)
    expect(items[0]?.textContent).toContain('鈴木一郎')
    expect(items[1]?.textContent).toContain('佐藤花子')
  })

  it('guardianSetupWarnings 空のときは警告ブロック非表示', async () => {
    await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries,
        notifyImmediately: true,
        guardianSetupWarnings: [],
      },
    })
    expect(findByTestId('roll-call-warnings')).toBeNull()
  })

  it('確定ボタンクリックで confirm を emit', async () => {
    const wrapper = await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries,
        notifyImmediately: true,
        guardianSetupWarnings: [],
      },
    })
    const btn = getByTestId<HTMLButtonElement>('roll-call-submit-confirm')
    btn.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('confirm')).toBeTruthy()
    expect(wrapper.emitted('confirm')!.length).toBe(1)
  })

  it('キャンセルボタンで cancel と update:visible(false) を emit', async () => {
    const wrapper = await mountSuspended(RollCallSubmitDialog, {
      props: {
        visible: true,
        entries,
        notifyImmediately: true,
        guardianSetupWarnings: [],
      },
    })
    const btn = getByTestId<HTMLButtonElement>('roll-call-submit-cancel')
    btn.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('cancel')).toBeTruthy()
    const visibleEmits = wrapper.emitted('update:visible')
    expect(visibleEmits).toBeTruthy()
    expect(visibleEmits?.[visibleEmits.length - 1]).toEqual([false])
  })
})
