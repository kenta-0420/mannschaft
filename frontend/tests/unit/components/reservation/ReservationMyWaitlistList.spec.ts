import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationMyWaitlistList from '~/components/reservation/ReservationMyWaitlistList.vue'

/**
 * ReservationMyWaitlistList.vue（自分のキャンセル待ち一覧・F03.4.5 §6.1 W2-4-FE）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: listMyWaitlist の応答をこのチーム（teamId=slug）分のみに絞り込んで表示する
 *         🔴teamId は slug（`pages/teams/[slug]/reservations.vue` が渡す）であり、
 *         `WaitlistEntryResponse.teamId` は BE の数値 DB id。両者は文字列一致しないため、
 *         `useActivityScopeId().resolveScopeId('TEAM', slug)` で数値解決してから絞り込む
 *         （検分で発覚した実バグ「slugと数値idの直接比較で常にfalse」の回帰防止・2026-07-30是正）。
 *         本テストは props.teamId に**実際の slug 値**（'team-slug'）を渡し、数値idではないことを
 *         明示したうえで絞り込みが成立することを検証する（数値文字列だと同じ穴を再び見逃すため）。
 *   AC-2: 0件のときは空状態を表示する
 *   AC-3: 取消ボタンで leaveWaitlist を呼び、成功後に一覧を再読込する
 *   AC-4: 取消失敗=RESERVATION_046（対象なし）は専用文言で通知し、一覧を再読込する
 *   AC-5（検分是正・2026-07-30）: props.teamId が別チームの slug に変わると数値解決をキャッシュせず
 *        再解決し、新チームの一覧に切り替わる（永続シェルで同一コンポーネントが別チームへ再利用される
 *        際、古いチームの数値idで絞り込み続けたまま誤表示する実バグの回帰防止）
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockListMyWaitlist = vi.fn()
const mockLeaveWaitlist = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    listMyWaitlist: mockListMyWaitlist,
    leaveWaitlist: mockLeaveWaitlist,
  }),
}))

const mockResolveScopeId = vi.fn()
vi.mock('~/composables/useActivityScopeId', () => ({
  useActivityScopeId: () => ({
    resolveScopeId: mockResolveScopeId,
  }),
}))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: mockNotifyError,
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

/** このチーム（slug='team-slug'）の数値DB idは10とする。resolveScopeId('TEAM', 'team-slug') → 10。 */
const SLUG = 'team-slug'
const entryTeam10 = {
  id: 'w-1', teamId: 10, slotId: 501, slotDate: '2026-08-01', startTime: '10:00:00', endTime: '10:30:00', slotTitle: 'Cut', status: 'WAITING',
}
const entryTeam99 = {
  id: 'w-2', teamId: 99, slotId: 502, slotDate: '2026-08-02', startTime: '11:00:00', endTime: '11:30:00', status: 'WAITING',
}

beforeEach(() => {
  mockListMyWaitlist.mockReset()
  mockLeaveWaitlist.mockReset()
  mockResolveScopeId.mockReset()
  mockResolveScopeId.mockResolvedValue(10)
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockHandleApiError.mockReset()
})

/**
 * ウォームアップマウント（殿の実測で確定した対処・2026-07-30）。
 *
 * `mountSuspended` の**初回**呼び出しは、当該コンポーネント（および依存ツリー）の
 * transform（esbuild/vite変換）コストをそのテストの `testTimeout`（既定5秒）内で負担する。
 * 環境が重いとき（実測: transform 130〜380秒/ファイル）、この初回コストだけで
 * 1件目のテスト（AC-1）が確定的に5秒を超えて timeout する
 * （2件目以降はコンパイル済みモジュールを再利用するため速い・ロジックの欠陥ではない）。
 *
 * `setupNuxt` 用に既に大きい hookTimeout を持つ `beforeAll` で使い捨てマウントし、
 * transform コストを beforeAll 側に前払いすることで、各 it は既定の testTimeout のまま
 * 安定させる。testTimeout を config 全体で引き上げると他テストの本物の hang を隠すため禁止
 * （殿の指示）。
 */
beforeAll(async () => {
  mockListMyWaitlist.mockResolvedValue({ data: [] })
  mockResolveScopeId.mockResolvedValue(10)
  const warmup = await mountSuspended(ReservationMyWaitlistList, { props: { teamId: 'warmup-slug' } })
  warmup.unmount()
})

describe('ReservationMyWaitlistList.vue', () => {
  it('AC-1: slug の teamId を渡しても数値解決を経て自チーム分のみを表示する（他チーム分は除外）', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10, entryTeam99] })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    // slug が数値 teamId と直接比較されていない（=resolveScopeId が実際に呼ばれ、その結果で絞り込んでいる）ことを保証する。
    expect(mockResolveScopeId).toHaveBeenCalledWith('TEAM', SLUG)
    expect(wrapper.text()).toContain('Cut')
    expect(wrapper.text()).toContain('2026-08-01')
    expect(wrapper.text()).not.toContain('2026-08-02')
  })

  it('AC-1b: slug の数値解決に失敗した場合は0件表示にフォールバックする（誤って全件/他チーム分を出さない）', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10, entryTeam99] })
    mockResolveScopeId.mockResolvedValue(null)

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    expect(wrapper.text()).toContain("You're not on any waitlists")
    expect(wrapper.text()).not.toContain('Cut')
  })

  it('AC-2: 0件のときは空状態を表示する', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    expect(wrapper.text()).toContain("You're not on any waitlists")
  })

  it('AC-3: 取消ボタンで leaveWaitlist を呼び、成功後に一覧を再読込する', async () => {
    mockListMyWaitlist.mockResolvedValueOnce({ data: [entryTeam10] })
    mockListMyWaitlist.mockResolvedValueOnce({ data: [] })
    mockLeaveWaitlist.mockResolvedValue({})

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    const cancelBtn = wrapper.find('[data-testid="my-waitlist-cancel-w-1"]')
    expect(cancelBtn.exists()).toBe(true)
    await cancelBtn.trigger('click')
    await flush()

    // leaveWaitlist 自体は BE の slug-or-id パスコンバータに委ねるため slug をそのまま渡す（数値解決不要）。
    expect(mockLeaveWaitlist).toHaveBeenCalledWith(SLUG, 501)
    expect(mockNotifySuccess).toHaveBeenCalled()
    expect(mockListMyWaitlist).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain("You're not on any waitlists")
  })

  it('AC-4: 取消失敗=RESERVATION_046（対象なし）は専用文言で通知し一覧を再読込する', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10] })
    mockLeaveWaitlist.mockRejectedValue({ data: { error: { code: 'RESERVATION_046' } } })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    const cancelBtn = wrapper.find('[data-testid="my-waitlist-cancel-w-1"]')
    await cancelBtn.trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalled()
    expect(mockListMyWaitlist).toHaveBeenCalledTimes(2)
    // ハンドラの共通経路（handleApiError）は呼ばない（専用分岐で処理済み）
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })

  it('AC-5（検分是正）: props.teamId が別チームへ変わると再解決して新チームの一覧に切り替わる', async () => {
    const OTHER_SLUG = 'other-team-slug'
    const entryTeam20 = {
      id: 'w-3', teamId: 20, slotId: 601, slotDate: '2026-09-01', startTime: '09:00:00', endTime: '09:30:00', slotTitle: 'Color', status: 'WAITING',
    }
    mockResolveScopeId.mockImplementation(async (_type: string, slug: string) => {
      if (slug === SLUG) return 10
      if (slug === OTHER_SLUG) return 20
      return null
    })
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10, entryTeam20] })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: SLUG },
    })
    await flush()

    expect(wrapper.text()).toContain('Cut')
    expect(wrapper.text()).not.toContain('Color')

    // 永続シェルでの再利用を模して props.teamId のみを別チームへ差し替える（コンポーネントは再mountしない）。
    await wrapper.setProps({ teamId: OTHER_SLUG })
    await flush()

    expect(mockResolveScopeId).toHaveBeenCalledWith('TEAM', OTHER_SLUG)
    // 古いチーム（数値id=10）のキャッシュのままにならず、新チーム（数値id=20）で絞り込み直されている。
    expect(wrapper.text()).toContain('Color')
    expect(wrapper.text()).not.toContain('Cut')
  })
})
