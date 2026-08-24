import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import ReservationForm from '~/components/reservation/ReservationForm.vue'
import ReservationList from '~/components/reservation/ReservationList.vue'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'
import ReservationUnavailabilityManager from '~/components/reservation/ReservationUnavailabilityManager.vue'
import ReservationResourceNameSettings from '~/components/reservation/ReservationResourceNameSettings.vue'
import LineManager from '~/components/reservation/LineManager.vue'

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({ team: ref({ timezone: 'America/New_York' }) }),
}))

/**
 * TeamReservationsPanel.vue ユニットテスト — 予約直後の再読込結線ガード（実機E2E発見バグの根治）
 *
 * 背景:
 *   実機E2E（予約v2第一弾）で、予約確定後に一覧・枠の空き状況が更新されない実バグを発見した。
 *   原因は ReservationForm が emit('reserved') する一方、TeamReservationsPanel が
 *   <ReservationForm @reserved="..."> を結線しておらず、ReservationList（一覧）・枠表示
 *   の再読込がトリガーされていなかったこと（再読込＝タブ切替や再訪問まで放置される）。
 *
 * 観点（AC 対応）:
 *   AC-1: reserved emit 後、枠表示（SlotMatrixPicker）の再取得（getSlotGrid）が再実行される
 *   AC-2: reserved emit 後、ReservationList の一覧再取得（listMyReservations）が再実行される
 *   AC-3（F03.4.4 追加 → 旧表示撤去で恒久化）: 予約タブの枠表示は SlotMatrixPicker 一本で、
 *     表示切替 UI（SelectButton）も localStorage の表示選好も存在しない
 *   AC-5（第二弾実機E2E発見バグの根治・#2179逆方向）: ReservationList の changed emit 後、
 *     枠表示（SlotMatrixPicker）の再取得が再実行される。一覧タブでの承認/却下/キャンセルが
 *     予約するタブの空き表示に反映されない実バグ（一覧→枠表示の逆方向が未結線）を根治する。
 *
 * 注: useRoleAccess を isAdmin=false/isAdminOrDeputy=false に固定し、ADMIN限定タブ
 *     （ライン管理・緊急休業）を DOM に出さない（v-if で最初から存在しないため mount 不要）。
 *     旧リスト表示（SlotPicker）・旧staff軸グリッド（SlotGridPicker）はマスター裁可 2026-08-04 で
 *     撤去済みのため、再読込結線の検証対象は SlotMatrixPicker に一本化している。
 */
const mockGetReservationSettings = vi.fn()
const mockGetLines = vi.fn()
const mockGetSlots = vi.fn()
const mockGetSlotGrid = vi.fn()
const mockGetMenus = vi.fn()
const mockListMyReservations = vi.fn()
const mockCreateReservation = vi.fn()
// 検分指摘（軽4）: 週間スケジュールの件数バッジ（枠テンプレ＋定期予約不可の合算）を検証するために追加。
const mockGetSlotTemplates = vi.fn()
const mockListRecurringBlockedTimes = vi.fn()
const mockListBlockedTimes = vi.fn()
const mockGetBusinessHours = vi.fn()
// SlotMatrixPicker が自分のキャンセル待ち集合の取得に使う（#2609是正: 未モックだと
// TypeError: reservationApi.listMyWaitlist is not a function が握りつぶされつつ毎回発生していた）。
const mockListMyWaitlist = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getReservationSettings: mockGetReservationSettings,
    getLines: mockGetLines,
    getSlots: mockGetSlots,
    getSlotGrid: mockGetSlotGrid,
    getMenus: mockGetMenus,
    listMyReservations: mockListMyReservations,
    createReservation: mockCreateReservation,
    getSlotTemplates: mockGetSlotTemplates,
    listRecurringBlockedTimes: mockListRecurringBlockedTimes,
    listBlockedTimes: mockListBlockedTimes,
    getBusinessHours: mockGetBusinessHours,
    listMyWaitlist: mockListMyWaitlist,
  }),
}))

// 管理タブ（TabPanel value=2）配下の EmergencyClosureForm が実網羅通信を試みないよう最小スタブ化する
// （TabPanels は非 lazy のため isAdmin/isAdminOrDeputy=true では実マウントされる）。
vi.mock('~/composables/useEmergencyClosureApi', () => ({
  useEmergencyClosureApi: () => ({
    resolveTeamId: vi.fn().mockResolvedValue(null),
    previewClosure: vi.fn().mockResolvedValue({ data: [] }),
    sendClosure: vi.fn().mockResolvedValue({ data: {} }),
    listClosures: vi.fn().mockResolvedValue({ data: [] }),
  }),
}))

/** useRoleAccess のロールを動的に切り替えるための可変オブジェクト（AC-6/AC-7 で admin=true に上書きする）。 */
const roleOverride = { isAdmin: false, isAdminOrDeputy: false, roleName: 'MEMBER' }

mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(roleOverride.isAdmin),
  isAdminOrDeputy: ref(roleOverride.isAdminOrDeputy),
  isMember: ref(true),
  roleName: ref(roleOverride.roleName),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))

mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn(), close: vi.fn() }))

const activeLine = { id: 10, meta: { name: 'テスト予約対象', isActive: true } }
const availableSlot = {
  id: 100,
  status: { slotStatus: 'AVAILABLE' },
  basic: { slotDate: '2026-07-10', startTime: '09:00', endTime: '10:00' },
}

beforeEach(() => {
  mockGetReservationSettings.mockReset()
  mockGetLines.mockReset()
  mockGetSlots.mockReset()
  mockGetSlotGrid.mockReset()
  mockGetMenus.mockReset()
  mockListMyReservations.mockReset()
  mockCreateReservation.mockReset()
  mockGetSlotTemplates.mockReset()
  mockListRecurringBlockedTimes.mockReset()
  mockListBlockedTimes.mockReset()
  mockGetBusinessHours.mockReset()
  mockListMyWaitlist.mockReset()
  localStorage.clear()

  mockGetReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
  mockGetLines.mockResolvedValue({ data: [activeLine] })
  mockGetSlots.mockResolvedValue({ data: [availableSlot] })
  mockGetSlotGrid.mockResolvedValue({ data: { days: [] } })
  mockGetMenus.mockResolvedValue({ data: [] })
  mockListMyReservations.mockResolvedValue({ data: [] })
  // 既定は枠テンプレ・定期予約不可とも0件（badge検証テストのみ個別に上書きする）。
  mockGetSlotTemplates.mockResolvedValue({ data: { templates: [], meta: { totalTemplates: 0, limit: 500 } } })
  mockListRecurringBlockedTimes.mockResolvedValue({ data: [] })
  mockListBlockedTimes.mockResolvedValue({ data: [] })
  mockGetBusinessHours.mockResolvedValue({ data: [] })
  // 自分がどの枠のキャンセル待ちにも登録していない既定状態（空配列が自然な初期値）。
  mockListMyWaitlist.mockResolvedValue({ data: [] })

  // 既定は非管理者（MEMBER）。admin タブ構成を検証するテストのみ個別に上書きする。
  roleOverride.isAdmin = false
  roleOverride.isAdminOrDeputy = false
  roleOverride.roleName = 'MEMBER'
})

describe('TeamReservationsPanel.vue 予約直後の再読込結線', () => {
  it('SlotMatrixPickerへTeamShellContextのタイムゾーンを渡す', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetMenus.mockResolvedValue({ data: [] })
    mockGetSlotGrid.mockResolvedValue({ data: { days: [] } })
    mockListMyWaitlist.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug', managementView: false },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).props('teamTimezone')).toBe('America/New_York')
  })

  it('ReservationUnavailabilityManagerへteam timezoneを渡す', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'

    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug', managementView: true },
    })
    await flushPromises()

    const manager = wrapper.findComponent(ReservationUnavailabilityManager)
    expect(manager.exists()).toBe(true)
    expect(manager.props('teamTimezone')).toBe('America/New_York')
  })

  it('ReservationUnavailabilityManager取得rangeはteam timezoneの日付を使う', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-13T02:00:00.000Z'))
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'

    await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug', managementView: true },
    })
    await flushPromises()

    expect(mockListBlockedTimes).toHaveBeenCalledWith('team-slug', {
      from: '2026-08-12',
      to: '2027-08-12',
    })
    vi.useRealTimers()
  })

  it('AC-1/2: ReservationForm の reserved emit で枠(SlotMatrixPicker)・一覧(ReservationList)が再読込される', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // mount 直後の初回読込回数（マトリックスは週レンジ・呼称ロード等で複数回呼びうるため
    // 絶対値ではなく「emit 前後の差分」で判定する）。
    const gridCallsBefore = mockGetSlotGrid.mock.calls.length
    const listCallsBefore = mockListMyReservations.mock.calls.length
    expect(gridCallsBefore).toBeGreaterThan(0)
    expect(listCallsBefore).toBeGreaterThan(0)

    // ReservationForm は常時 DOM 上に存在する（v-model:visible で開閉するのみ）。
    // 実際のダイアログ操作（Teleport 経由）を介さず、結線対象のイベントを直接発火して
    // 「reserved を受けたら再読込が走る」という結線契約そのものを検証する。
    const form = wrapper.findComponent(ReservationForm)
    expect(form.exists()).toBe(true)
    await form.vm.$emit('reserved')
    await flushPromises()

    // emit 後にそれぞれ最低1回追加で呼ばれていること = @reserved が正しく結線され
    // 枠(SlotMatrixPicker)・一覧(ReservationList)の再読込がトリガーされたことの証跡。
    expect(mockGetSlotGrid.mock.calls.length).toBeGreaterThan(gridCallsBefore)
    expect(mockListMyReservations.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })

  it('AC-3（旧表示撤去）: 予約タブの枠表示は SlotMatrixPicker 一本で、表示切替 UI も表示選好の localStorage 書き込みも存在しない', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    // マトリックスは from/to のレンジ呼びでグリッドAPIを叩く（機能C の date 単日呼びとは別経路）
    expect(mockGetSlotGrid).toHaveBeenCalled()
    // 旧リスト表示（SlotPicker）の単日枠API（getSlots）はもう呼ばれない
    expect(mockGetSlots).not.toHaveBeenCalled()
    // 表示切替 SelectButton は撤去済み（選択肢が1つしか残らない切替UIは無意味なため）
    expect(wrapper.findComponent({ name: 'SelectButton' }).exists()).toBe(false)
    // 表示選好の localStorage キーも撤去済み（書き込みが発生しないこと）
    expect(localStorage.getItem('mannschaft.reservation.bookDisplayMode')).toBeNull()
  })

  it('AC-4（F03.4.4）: SlotMatrixPicker の reserved emit で一覧(ReservationList)が再読込される', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const listCallsBefore = mockListMyReservations.mock.calls.length
    expect(listCallsBefore).toBeGreaterThan(0)

    const matrix = wrapper.findComponent(SlotMatrixPicker)
    expect(matrix.exists()).toBe(true)
    await matrix.vm.$emit('reserved')
    await flushPromises()

    expect(mockListMyReservations.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })

  it('AC-5: ReservationList の changed emit で枠(SlotMatrixPicker)の再読込が発火する（一覧→枠表示の逆方向結線）', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const gridCallsBefore = mockGetSlotGrid.mock.calls.length
    expect(gridCallsBefore).toBeGreaterThan(0)

    // ReservationList は TabPanels 非 lazy のため一覧タブが非アクティブでも実マウント済み。
    const list = wrapper.findComponent(ReservationList)
    expect(list.exists()).toBe(true)
    await list.vm.$emit('changed')
    await flushPromises()

    expect(mockGetSlotGrid.mock.calls.length).toBeGreaterThan(gridCallsBefore)
  })

  it('AC-6（UX改善5点の5）: 非管理者（MEMBER）はタブが「予約する」「自分の予約」の2つのみで、ラベルは自分の予約', async () => {
    // beforeEach 既定（isAdmin=false, isAdminOrDeputy=false, roleName=MEMBER）のまま
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(2)
    expect(tabs[0]!.text()).toBe('Book')
    expect(tabs[1]!.text()).toBe('My Reservations')
  })

  it('AC-6b: SUPPORTER も非管理者側としてタブが2つのみ', async () => {
    roleOverride.isAdmin = false
    roleOverride.isAdminOrDeputy = false
    roleOverride.roleName = 'SUPPORTER'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(2)
    expect(tabs[1]!.text()).toBe('My Reservations')
  })

  it('AC-7（UX改善5点の5・認可挙動不変の確認）: 管理者はタブが4つ（予約する/予約一覧/予約対象の管理/緊急休業）で不変', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(4)
    expect(tabs[0]!.text()).toBe('Book')
    // 管理者/副管理者（mode=team）はラベル改名の対象外で従来の「予約一覧」のまま
    expect(tabs[1]!.text()).toBe('Reservations')
    // F03.4.5 §5.2: 呼称の動的差し込み。resourceNameType 未設定（DEFAULT）は
    // 従来どおり「Bookable Item」相当のフォールバックのため、テキストは実質不変。
    expect(tabs[2]!.text()).toBe('Bookable Item Management')
    expect(tabs[3]!.text()).toBe('緊急休業')
  })

  it('管理者がメンバー表示へ切り替えた場合は予約と自分の予約だけを表示する', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug', managementView: false },
    })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(2)
    expect(tabs[0]!.text()).toBe('Book')
    expect(tabs[1]!.text()).toBe('My Reservations')

    const list = wrapper.findComponent(ReservationList)
    expect(list.props('mode')).toBe('mine')
    expect(list.props('canManage')).toBe(false)
    expect(wrapper.findComponent(LineManager).exists()).toBe(false)
  })

  it('管理タブを開いた状態でメンバー表示へ切り替えた場合は予約タブへ戻る', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug', managementView: true },
    })
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[2]!.trigger('click')
    await wrapper.setProps({ managementView: false })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(2)
    expect(tabs[0]!.attributes('aria-selected')).toBe('true')
  })

  it('予約画面には別機能のイベント導線を表示しない', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="reservation-event-link"]').exists()).toBe(false)
  })

  it('AC-8（旧表示撤去に伴う置換・UX改善5点の3）: タブ往復（予約する→予約一覧→予約する）でマトリックスは破棄されず再mountしない', async () => {
    // 旧 AC-8 は「表示切替（マトリックス↔リスト）で KeepAlive により破棄されない」ことを検証していたが、
    // 表示切替 UI ごと撤去したため前提が消滅した。状態保持という観点そのものは、TabPanels 非 lazy による
    // 「タブ往復でも破棄されない」（＝スクロール位置・取得済みデータが維持される）で引き継ぐ。
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    // getMenus は SlotMatrixPicker の onMounted のみが呼ぶ → 再mountの検出器として使う
    const menusCallsBefore = mockGetMenus.mock.calls.length
    expect(menusCallsBefore).toBeGreaterThan(0)

    const tabs = wrapper.findAll('[role="tab"]')
    await tabs[1]!.trigger('click')
    await flushPromises()
    await tabs[0]!.trigger('click')
    await flushPromises()

    // 破棄されていない証跡: 再mountなら onMounted の loadMenus が再実行されるはずだが、増えていない
    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    expect(mockGetMenus.mock.calls.length).toBe(menusCallsBefore)
    // 表示保持＝skeleton へ切り替わらない（loading を立て直さない）
    expect(wrapper.findComponent(SlotMatrixPicker).findAllComponents({ name: 'Skeleton' })).toHaveLength(0)
  })

  it('AC-FE11（F03.4.5 W2-1第二隊の番人）: 管理タブは①営業時間→②予約対象→③メニュー→④週間スケジュール→⑤例外日カレンダー→⑥詳細設定（個別の枠を手動管理）の順で構成される', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // テスト環境の既定ロケールは en（写経元 WeeklyScheduleManager.spec.ts と同じ前提）。
    // F03.4.5 §5.2 により②予約対象の badge とタブ2ラベルが同一文言（"Bookable Item Management"）に
    // なったため、badge を件数サフィックス付きの文字列で特定してタブ位置と誤認しないようにする。
    const text = wrapper.text()
    const idxBusinessHours = text.indexOf('Business hours')
    const idxLines = text.indexOf('Bookable Item Management (1)')
    const idxMenus = text.indexOf('Menu Management')
    const idxWeekly = text.indexOf('Weekly Templates')
    const idxException = text.indexOf('Exception day calendar')
    const idxAdvanced = text.indexOf('Advanced Settings')
    // SlotManager が⑥詳細設定内で「個別の枠を手動管理（例外操作）」ラベルを持つこと（F03.4.5 §3.2）
    const idxSlotManageLabel = text.indexOf('Manually manage individual slots (exceptions)')

    for (const [label, idx] of [
      ['business_hours', idxBusinessHours],
      ['lines', idxLines],
      ['menus', idxMenus],
      ['weekly_schedule', idxWeekly],
      ['exception_day', idxException],
      ['advanced', idxAdvanced],
      ['slot_manage_label', idxSlotManageLabel],
    ] as const) {
      expect(idx, `${label} の見出し/ラベルが描画されていること`).toBeGreaterThan(-1)
    }

    expect(idxBusinessHours, '①営業時間 → ②予約対象 の順').toBeLessThan(idxLines)
    expect(idxLines, '②予約対象 → ③メニュー の順').toBeLessThan(idxMenus)
    expect(idxMenus, '③メニュー → ④週間スケジュール の順').toBeLessThan(idxWeekly)
    expect(idxWeekly, '④週間スケジュール → ⑤例外日カレンダー の順').toBeLessThan(idxException)
    expect(idxException, '⑤例外日カレンダー → ⑥詳細設定 の順').toBeLessThan(idxAdvanced)
    expect(idxAdvanced, '⑥詳細設定の内側に「個別の枠を手動管理」ラベル（SlotManager）がある').toBeLessThan(idxSlotManageLabel)
  })

  it('AC-BADGE1（検分指摘・軽4）: 週間スケジュールの件数バッジは枠テンプレ＋定期予約不可ルールの合算になる（定期不可のみ登録時に(0)と誤表示しない）', async () => {
    // 旧実装は `templateCount = slotTemplateManagerRef.value?.items?.length`（枠テンプレのみ参照）だったため、
    // 定期予約不可枠だけ登録したチームで実データがあるのにバッジが (0) と表示され利用者を欺いていた。
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    // 枠テンプレは0件のまま、定期予約不可ルールのみ2件登録済みのチームを再現する。
    mockGetSlotTemplates.mockResolvedValue({ data: { templates: [], meta: { totalTemplates: 0, limit: 500 } } })
    mockListRecurringBlockedTimes.mockResolvedValue({
      data: [
        { id: 'rule-1', teamId: 10, lineId: null, lineName: null, dayOfWeek: 'TUE', startTime: '19:00:00', endTime: '20:00:00', reason: 'Training', isPublic: false, isActive: true },
        { id: 'rule-2', teamId: 10, lineId: null, lineName: null, dayOfWeek: 'WED', startTime: '09:00:00', endTime: '12:00:00', reason: 'Cleaning', isPublic: true, isActive: true },
      ],
    })

    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // 新しいラベル（枠テンプレ限定の「Weekly Templates」ではなく、両方を包含する「Weekly schedule」）に
    // 合算件数(2)が乗ること。旧実装なら templates.length=0 のため "(0)" になっていたはずの箇所。
    expect(wrapper.text(), 'テンプレ0件・定期不可2件で合算(2)がバッジに出ること').toContain('Weekly schedule (2)')
    expect(wrapper.text()).not.toContain('Weekly schedule (0)')
  })

  it('AC-DEPUTY-1（マスター裁可2026-07-11）: DEPUTY_ADMIN は②予約対象タブが見える（呼称設定のためタブ開放）', async () => {
    // 副管理者: isAdmin=false・isAdminOrDeputy=true。②タブが表示され、タブ数は
    // 予約する/予約一覧/②予約対象/緊急休業 の4つ（緊急休業も isAdminOrDeputy 表示）。
    roleOverride.isAdmin = false
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'DEPUTY_ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    // 予約する / 予約一覧 / ②予約対象(呼称) / 緊急休業
    expect(tabs).toHaveLength(4)
    // 管理者/副管理者は予約一覧ラベル（自分の予約ではない）
    expect(tabs[1]!.text()).toBe('Reservations')
    // ②タブが存在し「Bookable Item Management」ラベル（DEFAULT フォールバック）
    expect(tabs[2]!.text()).toBe('Bookable Item Management')
  })

  it('AC-DEPUTY-2（マスター裁可2026-07-11）: DEPUTY_ADMIN の②タブは呼称設定のみ編集可・ライン/メニュー管理は非表示（ADMIN限定維持）', async () => {
    roleOverride.isAdmin = false
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'DEPUTY_ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // 呼称設定コンポーネントが描画され、disabled=false（=編集可）で渡っている
    const resourceNameSettings = wrapper.findComponent(ReservationResourceNameSettings)
    expect(resourceNameSettings.exists()).toBe(true)
    expect(resourceNameSettings.props('disabled')).toBe(false)

    // ライン管理は ADMIN 限定のため DEPUTY_ADMIN には描画されない（物理的に非表示＝閲覧すら不可）
    expect(wrapper.findComponent(LineManager).exists()).toBe(false)
    // 副管理者向けの注意文（呼称のみ変更可）が表示される
    expect(wrapper.text()).toContain('Deputy admins can only change')
  })

  it('AC-DEPUTY-3: ADMIN は従来どおり②タブでライン管理＋呼称設定の両方が編集可（回帰確認）', async () => {
    roleOverride.isAdmin = true
    roleOverride.isAdminOrDeputy = true
    roleOverride.roleName = 'ADMIN'
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // ADMIN はライン管理・呼称設定の両方が存在し、呼称は編集可（disabled=false）
    expect(wrapper.findComponent(LineManager).exists()).toBe(true)
    const resourceNameSettings = wrapper.findComponent(ReservationResourceNameSettings)
    expect(resourceNameSettings.exists()).toBe(true)
    expect(resourceNameSettings.props('disabled')).toBe(false)
    // ADMIN には副管理者向け注意文は出さない（DEPUTY 専用ビューではないため）
    expect(wrapper.text()).not.toContain('Deputy admins can only change')
  })
})
