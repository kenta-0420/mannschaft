import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import RollCallEntryRow from '~/components/event/rollCall/RollCallEntryRow.vue'
import type { RollCallCandidate, RollCallEntry } from '~/types/care'

/**
 * F03.12 §14 RollCallEntryRow.vue のユニットテスト。
 *
 * <h3>カバー範囲</h3>
 * <ul>
 *   <li>PRESENT を選ぶと {@code update:modelValue} が status=PRESENT で発火し、
 *       前回値の {@code lateArrivalMinutes} / {@code absenceReason} はクリアされる</li>
 *   <li>LATE を選ぶと lateArrivalMinutes が初期値（5）で乗る、absenceReason はクリア</li>
 *   <li>ABSENT を選ぶと absenceReason が初期値（NOT_ARRIVED）で乗る、lateArrivalMinutes はクリア</li>
 * </ul>
 */

function sampleCandidate(overrides: Partial<RollCallCandidate> = {}): RollCallCandidate {
  return {
    userId: 101,
    displayName: '山田太郎',
    avatarUrl: null,
    rsvpStatus: 'ATTENDING',
    isAlreadyCheckedIn: false,
    isUnderCare: false,
    watcherCount: 0,
    ...overrides,
  }
}

describe('RollCallEntryRow.vue', () => {
  /**
   * 直近の {@code update:modelValue} で emit された RollCallEntry を取り出す。
   *
   * <p>{@code wrapper.emitted('update:modelValue')} は {@code unknown[][] | undefined} を返すため、
   * 1 段ずつ型ガードを噛ませて payload を取り出す。</p>
   */
  function lastEntry(wrapper: { emitted: (name: string) => unknown[][] | undefined }): RollCallEntry {
    const events = wrapper.emitted('update:modelValue')
    if (!events || events.length === 0) {
      throw new Error('update:modelValue が emit されていません')
    }
    const last = events[events.length - 1]
    if (!last || last.length === 0) {
      throw new Error('emit ペイロードが空です')
    }
    return last[0] as RollCallEntry
  }

  it('PRESENT 選択時: status=PRESENT で emit、補助項目はクリア', async () => {
    const candidate = sampleCandidate()
    const wrapper = await mountSuspended(RollCallEntryRow, {
      props: {
        candidate,
        modelValue: { userId: 101, status: 'LATE', lateArrivalMinutes: 30 } satisfies RollCallEntry,
      },
    })
    const btn = wrapper.get('[data-testid="roll-call-row-101-present"]')
    await btn.trigger('click')

    const payload = lastEntry(wrapper)
    expect(payload.status).toBe('PRESENT')
    expect(payload.userId).toBe(101)
    expect(payload.lateArrivalMinutes).toBeUndefined()
    expect(payload.absenceReason).toBeUndefined()
  })

  it('LATE 選択時: lateArrivalMinutes が初期値 5 で乗る、absenceReason はクリア', async () => {
    const candidate = sampleCandidate()
    const wrapper = await mountSuspended(RollCallEntryRow, {
      props: {
        candidate,
        modelValue: { userId: 101, status: 'ABSENT', absenceReason: 'SICK' } satisfies RollCallEntry,
      },
    })
    const btn = wrapper.get('[data-testid="roll-call-row-101-late"]')
    await btn.trigger('click')

    const payload = lastEntry(wrapper)
    expect(payload.status).toBe('LATE')
    expect(payload.lateArrivalMinutes).toBe(5)
    expect(payload.absenceReason).toBeUndefined()
  })

  it('ABSENT 選択時: absenceReason が NOT_ARRIVED で乗る、lateArrivalMinutes はクリア', async () => {
    const candidate = sampleCandidate()
    const wrapper = await mountSuspended(RollCallEntryRow, {
      props: {
        candidate,
        modelValue: { userId: 101, status: 'LATE', lateArrivalMinutes: 20 } satisfies RollCallEntry,
      },
    })
    const btn = wrapper.get('[data-testid="roll-call-row-101-absent"]')
    await btn.trigger('click')

    const payload = lastEntry(wrapper)
    expect(payload.status).toBe('ABSENT')
    expect(payload.absenceReason).toBe('NOT_ARRIVED')
    expect(payload.lateArrivalMinutes).toBeUndefined()
  })

  it('LATE→LATE 再選択時: 既存の lateArrivalMinutes を維持する', async () => {
    const candidate = sampleCandidate()
    const wrapper = await mountSuspended(RollCallEntryRow, {
      props: {
        candidate,
        modelValue: { userId: 101, status: 'LATE', lateArrivalMinutes: 20 } satisfies RollCallEntry,
      },
    })
    const btn = wrapper.get('[data-testid="roll-call-row-101-late"]')
    await btn.trigger('click')

    const payload = lastEntry(wrapper)
    expect(payload.status).toBe('LATE')
    expect(payload.lateArrivalMinutes).toBe(20)
  })

  it('isAlreadyCheckedIn=true / isUnderCare=true / watcherCount=0 のとき要素が描画される', async () => {
    const wrapper = await mountSuspended(RollCallEntryRow, {
      props: {
        candidate: sampleCandidate({
          isAlreadyCheckedIn: true,
          isUnderCare: true,
          watcherCount: 0,
        }),
        modelValue: undefined,
      },
    })
    // 各バッジは label がロケールキーになっているので、中身そのものではなく要素の存在のみ確認する
    const html = wrapper.html()
    expect(html).toContain('rc-badge--checked')
    expect(html).toContain('rc-badge--care')
    expect(html).toContain('rc-badge--warn')
  })
})
