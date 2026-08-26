import { describe, it, expect, vi, beforeEach, beforeAll, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import GroupBookingDialog from '~/components/reservation/GroupBookingDialog.vue'
import type { GroupBookingContext } from '~/components/reservation/GroupBookingDialog.vue'
import { buildTimeHeader, alignRowToHeader, type MatrixCellInput } from '~/utils/reservationMatrix'

/**
 * GroupBookingDialog.vue（F03.4.4 §5.3 予約フロー）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: メニュー選択で必要枠数分の連続セルが自動選択されプレビューへ進む
 *   AC-2: ＋30分延長ボタンの disabled 反映（次セル無し/非AVAILABLE/上限16）
 *   AC-3: N=1・メニューなしの確定は createReservation を呼ぶ（グループAPIではない・単枠フロー完全互換）
 *   AC-4: N>=2 の確定は createGroup を slotIds 昇順（時間昇順）で呼ぶ
 *   AC-5: エラーコード別の表示分岐（039=conflict_retry+reserved emit / 043=line_not_available で留まる /
 *         013=own_overlap専用文言でプレビューに留まる。第二弾実機E2E発見バグの根治）
 *   AC-6（検分是正・W2-6-FE）: 仮押さえ自動失効の会員向け注意書き（MANUAL×非NULLで表示・
 *         NULL/AUTOで非表示。ReservationForm.vue と同一方針をグループ経路でも検証）
 *   AC-7（検分是正・W2-6-FE）: 429=RESERVATION_053（グループ経路）は専用文言のトーストを表示する
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport で document.body にレンダリングされる。
 */
const mockCreateReservation = vi.fn()
const mockCreateGroup = vi.fn()
const mockGetReservationSettings = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    createReservation: mockCreateReservation,
    createGroup: mockCreateGroup,
    getReservationSettings: mockGetReservationSettings,
  }),
}))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  error: mockNotifyError,
  info: vi.fn(),
  warn: vi.fn(),
}))

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

/** 30分×4枠（10:00〜12:00・全AVAILABLE）の行コンテキストを構築する。 */
function buildContext(overrides?: Partial<GroupBookingContext>): GroupBookingContext {
  const cells: MatrixCellInput[] = [
    { slotId: 101, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
    { slotId: 102, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
    { slotId: 103, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
    { slotId: 104, startTime: '11:30', endTime: '12:00', state: 'AVAILABLE' },
  ]
  const header = buildTimeHeader(cells)
  const rowSlots = alignRowToHeader(cells, header)
  return {
    date: '2026-07-10',
    columnLineId: 1,
    columnLineName: '席1',
    rowSlots,
    startIndex: 0,
    header,
    preselectedMenuId: null,
    preselectedRequiredCellCount: null,
    ...overrides,
  }
}

const cutMenu = { id: 'menu-cut', name: 'Cut', durationMinutes: 60, requiredSlotCount: 2, isActive: true, lineIds: [] }

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

beforeEach(() => {
  mockCreateReservation.mockReset()
  mockCreateGroup.mockReset()
  mockGetReservationSettings.mockReset()
  mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

/**
 * ウォームアップマウント（殿の実測・家老の実走で確定した対処・2026-07-30是正）。
 * `mountSuspended` の初回呼び出しの transform コストを beforeAll 側に前払いし、各 it は
 * 既定の testTimeout のまま安定させる（`ReservationMyWaitlistList.spec.ts` と同一の対処）。
 */
beforeAll(async () => {
  mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
  const warmup = await mountSuspended(GroupBookingDialog, {
    props: {
      visible: true,
      teamId: 'warmup-slug',
      lines: [{ id: 1, name: '席1' }],
      menus: [cutMenu],
      context: buildContext(),
    },
  })
  warmup.unmount()
})

describe('GroupBookingDialog.vue', () => {
  it('AC-1: メニュー選択で必要枠数（2枠）が自動選択されプレビューへ進む', async () => {
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    const menuBtn = findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')
    expect(menuBtn).not.toBeNull()
    menuBtn!.click()
    await flush()

    // プレビューへ遷移＝確定ボタンが出現する
    expect(findByTestId('group-confirm')).not.toBeNull()
    // メニュー選択ボタンはもう表示されない
    expect(findByTestId('group-menu-option-menu-cut')).toBeNull()
  })

  it('AC-2: 直後セルが AVAILABLE な間は延長でき、末尾（12:00）到達で延長不可になる', async () => {
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()
    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()

    // 2枠選択済み（10:00-11:00）。次（11:00-11:30）が AVAILABLE のため延長可
    const extendBtn = findByTestId<HTMLButtonElement>('group-extend')
    expect(extendBtn).not.toBeNull()
    expect(extendBtn!.disabled).toBe(false)

    extendBtn!.click()
    await flush()
    // 3枠目まで延長（10:00-11:30）。次（11:30-12:00）も AVAILABLE のため延長可のまま
    expect(findByTestId<HTMLButtonElement>('group-extend')!.disabled).toBe(false)

    findByTestId<HTMLButtonElement>('group-extend')!.click()
    await flush()
    // 4枠目まで延長（10:00-12:00）。次のセルが存在しない（終端）ため延長不可
    expect(findByTestId<HTMLButtonElement>('group-extend')!.disabled).toBe(true)
  })

  it('AC-2a: ステッパーはメニューの必要枠数未満へ減らせず、延長分だけを戻せる', async () => {
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: 'Seat1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()
    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()

    expect(findByTestId<HTMLButtonElement>('group-reduce')!.disabled).toBe(true)
    findByTestId<HTMLButtonElement>('group-extend')!.click()
    await flush()
    expect(findByTestId<HTMLButtonElement>('group-reduce')!.disabled).toBe(false)
    expect(findByTestId('group-slot-stepper')!.textContent).toContain('3')
    expect(document.body.textContent).toContain('10:00 - 11:30')

    findByTestId<HTMLButtonElement>('group-reduce')!.click()
    await flush()
    expect(findByTestId<HTMLButtonElement>('group-reduce')!.disabled).toBe(true)
    expect(findByTestId('group-slot-stepper')!.textContent).toContain('2')
    expect(document.body.textContent).toContain('10:00 - 11:00')
  })

  it('AC-3: N=1・メニューなしの確定は createReservation を呼ぶ（createGroup は呼ばない）', async () => {
    mockCreateReservation.mockResolvedValue({ data: {} })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-no-menu')!.click()
    await flush()

    const confirmBtn = findByTestId<HTMLButtonElement>('group-confirm')
    expect(confirmBtn).not.toBeNull()
    expect(confirmBtn!.disabled).toBe(false)
    confirmBtn!.click()
    await flush()

    expect(mockCreateReservation).toHaveBeenCalledWith('team-slug', expect.objectContaining({
      reservationSlotId: 101,
      lineId: 1,
    }))
    expect(mockCreateGroup).not.toHaveBeenCalled()
    expect(mockNotifySuccess).toHaveBeenCalled()
  })

  it('AC-4: N>=2 の確定は createGroup を slotIds 昇順（時間昇順・連続）で呼ぶ', async () => {
    mockCreateGroup.mockResolvedValue({ data: {} })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush()

    expect(mockCreateGroup).toHaveBeenCalledWith('team-slug', expect.objectContaining({
      menuId: 'menu-cut',
      lineId: 1,
      slotIds: [101, 102],
    }))
    expect(mockCreateReservation).not.toHaveBeenCalled()
  })

  it('AC-5a: 409=RESERVATION_039 は conflict_retry を表示し reserved を emit してダイアログを閉じる', async () => {
    mockCreateGroup.mockRejectedValue({ data: { error: { code: 'RESERVATION_039' } } })
    const wrapper = await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush()

    expect(mockNotifyError).toHaveBeenCalled()
    expect(wrapper.emitted('reserved')).toBeTruthy()
    expect(wrapper.emitted('update:visible')).toBeTruthy()
  })

  it('AC-5b: 400=RESERVATION_043（提供不可ライン）はプレビューに留まり reserved は emit しない', async () => {
    mockCreateGroup.mockRejectedValue({ data: { error: { code: 'RESERVATION_043' } } })
    const wrapper = await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush()

    // プレビューに留まる（確定ボタンがまだ存在する＝閉じていない）
    expect(findByTestId('group-confirm')).not.toBeNull()
    expect(wrapper.emitted('reserved')).toBeFalsy()
  })

  it('AC-5c: 409=RESERVATION_013（自分の予約済み枠と重複）は専用文言でエラー表示しプレビューに留まる（reserved は emit しない）', async () => {
    mockCreateGroup.mockRejectedValue({ data: { error: { code: 'RESERVATION_013' } } })
    const wrapper = await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush()

    // 汎用「予約に失敗しました」(reserve_failed) ではなく own_overlap 専用文言でエラー表示する。
    expect(mockNotifyError).toHaveBeenCalledWith('You already have another booking in the selected time slot. Please check the time')
    // プレビューに留まる（確定ボタンがまだ存在する＝閉じていない）。039/038/009 と異なり選択し直しを促す。
    expect(findByTestId('group-confirm')).not.toBeNull()
    expect(wrapper.emitted('reserved')).toBeFalsy()
  })

  it('AC-6a: MANUAL かつ pendingExpireHours 非NULL のときプレビュー画面に注意書きを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'MANUAL', pendingExpireHours: 24 } })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    // N=1・メニューなしでプレビューへ進む（最短経路）
    findByTestId<HTMLButtonElement>('group-no-menu')!.click()
    await flush()

    const notice = findByTestId('pending-expire-notice')
    expect(notice).not.toBeNull()
    expect(notice!.textContent).toContain('24')
  })

  it('AC-6b: pendingExpireHours が NULL（自動失効なし設定）のときは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'MANUAL', pendingExpireHours: null } })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-no-menu')!.click()
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-6c: 承認モード=AUTO のチームは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-no-menu')!.click()
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-7: 429=RESERVATION_053（グループ経路）は専用文言のトーストを表示する', async () => {
    mockCreateGroup.mockRejectedValue({ data: { error: { code: 'RESERVATION_053' } } })
    await mountSuspended(GroupBookingDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        lines: [{ id: 1, name: '席1' }],
        menus: [cutMenu],
        context: buildContext(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('group-menu-option-menu-cut')!.click()
    await flush()
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush()

    expect(mockNotifyError).toHaveBeenCalledWith("You're creating reservations too quickly. Please wait a moment and try again")
  })
})
