import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import SlotPicker from '~/components/reservation/SlotPicker.vue'

/**
 * SlotPicker.vue（チーム予約「予約する」タブ）ユニットテスト — 空状態の役割別導線ガード
 *
 * 背景（導線根治）:
 *   従来は予約対象(Line)ゼロでも枠ゼロでも一律「空き枠なし」で行き止まりだった。
 *   本テストは「誰が見ているか（isAdmin）× なぜ空か（対象ゼロ / 枠ゼロ）」で
 *   文言・CTAが正しく出し分くことを固定する。
 *
 * 観点（AC 対応）:
 *   AC-1/2: ADMIN・予約対象0件 → 「予約対象がまだない」案内＋CTA「予約対象の管理へ」→ クリックで manageLines emit
 *   AC-3:   ADMIN・対象あり枠0件 → 「空き枠なし」＋CTA「枠を管理する」→ クリックで manageLines emit
 *   AC-4:   非管理・予約対象0件 → 「受付準備中」案内・管理CTAなし
 *   AC-5:   非管理・枠0件 → 「空き枠なし・別日を」案内・管理CTAなし
 *
 * 注: テスト環境の既定ロケールは en。i18n 実解決の描画文字列（英語）で検証する。
 */
const mockGetLines = vi.fn()
const mockGetSlots = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getLines: mockGetLines,
    getSlots: mockGetSlots,
  }),
}))

// authStore 依存を避けるため userTimezone を固定値でモックする
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

// i18n 実解決文字列（en ロケール）
const MSG_ADMIN_NO_LINES = 'No reservation targets yet'
const MSG_ADMIN_NO_SLOTS = 'No open slots on this day'
const MSG_MEMBER_NO_LINES = 'Reservations are being set up'
const MSG_MEMBER_NO_SLOTS_HINT = 'Please try another day'
const MSG_OLD_MISDIRECT = 'No available slots on this day' // 旧・一律の誤誘導文言
const CTA_GO_TO_LINE = 'Go to manage reservation targets'
const CTA_MANAGE_SLOTS = 'Manage slots'

/** meta.isActive を持つ有効な予約対象。 */
const activeLine = { id: 1, meta: { name: 'テスト予約対象', isActive: true } }

/** onMounted 内の loadLines→loadSlots（2連続 await）を確実に流し切る。 */
async function flush() {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

beforeEach(() => {
  mockGetLines.mockReset()
  mockGetSlots.mockReset()
})

describe('SlotPicker.vue 空状態の役割別出し分け', () => {
  it('AC-1/2: ADMIN・予約対象0件で対象作成の案内とCTAを出し、クリックで manageLines を emit する', async () => {
    mockGetLines.mockResolvedValue({ data: [] })
    mockGetSlots.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(SlotPicker, {
      props: { teamId: 'team-slug', isAdmin: true },
    })
    await flush()

    const html = wrapper.html()
    expect(html).toContain(MSG_ADMIN_NO_LINES)
    expect(html).not.toContain(MSG_OLD_MISDIRECT) // 旧・誤誘導文言が出ない
    expect(html).toContain(CTA_GO_TO_LINE)

    // CTA ボタン（ラベル一致）をクリックすると manageLines が飛ぶ
    const cta = wrapper.findAll('button').find((b) => b.text().includes(CTA_GO_TO_LINE))
    expect(cta).toBeTruthy()
    await cta!.trigger('click')
    expect(wrapper.emitted('manageLines')).toBeTruthy()
  })

  it('AC-3: ADMIN・予約対象あり・枠0件で枠追加のCTA（枠を管理する）を出し、クリックで manageLines を emit する', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlots.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(SlotPicker, {
      props: { teamId: 'team-slug', isAdmin: true },
    })
    await flush()

    const html = wrapper.html()
    expect(html).toContain(MSG_ADMIN_NO_SLOTS)
    expect(html).toContain(CTA_MANAGE_SLOTS)
    expect(html).not.toContain(MSG_ADMIN_NO_LINES)

    const cta = wrapper.findAll('button').find((b) => b.text().includes(CTA_MANAGE_SLOTS))
    expect(cta).toBeTruthy()
    await cta!.trigger('click')
    expect(wrapper.emitted('manageLines')).toBeTruthy()
  })

  it('AC-4: 非管理・予約対象0件は受付準備中を出し、管理CTAは出さない', async () => {
    mockGetLines.mockResolvedValue({ data: [] })
    mockGetSlots.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(SlotPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    const html = wrapper.html()
    expect(html).toContain(MSG_MEMBER_NO_LINES)
    expect(html).not.toContain(CTA_GO_TO_LINE)
    expect(html).not.toContain(CTA_MANAGE_SLOTS)
  })

  it('AC-5: 非管理・枠0件は別日案内を出し、管理CTAは出さない', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlots.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(SlotPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    const html = wrapper.html()
    expect(html).toContain(MSG_ADMIN_NO_SLOTS) // 枠ゼロの見出し（管理者と共通文言）
    expect(html).toContain(MSG_MEMBER_NO_SLOTS_HINT)
    expect(html).not.toContain(CTA_MANAGE_SLOTS)
    expect(html).not.toContain(CTA_GO_TO_LINE)
  })
})
