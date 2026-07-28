import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import ReservationForm from '~/components/reservation/ReservationForm.vue'
import ReservationList from '~/components/reservation/ReservationList.vue'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'
import SlotPicker from '~/components/reservation/SlotPicker.vue'
import SlotGridPicker from '~/components/reservation/SlotGridPicker.vue'
import ReservationResourceNameSettings from '~/components/reservation/ReservationResourceNameSettings.vue'
import LineManager from '~/components/reservation/LineManager.vue'

/**
 * TeamReservationsPanel.vue ユニットテスト — 予約直後の再読込結線ガード（実機E2E発見バグの根治）
 *
 * 背景:
 *   実機E2E（予約v2第一弾）で、予約確定後に一覧・枠の空き状況が更新されない実バグを発見した。
 *   原因は ReservationForm が emit('reserved') する一方、TeamReservationsPanel が
 *   <ReservationForm @reserved="..."> を結線しておらず、ReservationList（一覧）・SlotPicker（枠）
 *   の再読込がトリガーされていなかったこと（再読込＝タブ切替や再訪問まで放置される）。
 *
 * 観点（AC 対応）:
 *   AC-1: reserved emit 後、SlotPicker の枠再取得（getSlots）が再実行される
 *   AC-2: reserved emit 後、ReservationList の一覧再取得（listMyReservations）が再実行される
 *   AC-3（F03.4.4 追加）: 表示選好 localStorage が未設定の場合、既定タブは SlotMatrixPicker（マトリックス）
 *   AC-5（第二弾実機E2E発見バグの根治・#2179逆方向）: ReservationList の changed emit 後、
 *     SlotPicker の枠再取得（getSlots）が再実行される。一覧タブでの承認/却下/キャンセルが
 *     予約するタブの空き表示に反映されない実バグ（一覧→枠表示の逆方向が未結線）を根治する。
 *
 * 注: useRoleAccess を isAdmin=false/isAdminOrDeputy=false に固定し、ADMIN限定タブ
 *     （ライン管理・緊急休業）を DOM に出さない（v-if で最初から存在しないため mount 不要）。
 *     AC-1/2 は SlotPicker 固有の再読込結線を検証する観点のため、localStorage に
 *     表示選好 'list' を事前設定して SlotPicker を実マウントさせる（F03.4.4 で既定が
 *     'matrix' へ変わったため。§5.4 の localStorage 記憶方針に基づく明示的な選好切替）。
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
  localStorage.clear()

  mockGetReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
  mockGetLines.mockResolvedValue({ data: [activeLine] })
  mockGetSlots.mockResolvedValue({ data: [availableSlot] })
  mockGetSlotGrid.mockResolvedValue({ data: { axis: 'LINE', days: [] } })
  mockGetMenus.mockResolvedValue({ data: [] })
  mockListMyReservations.mockResolvedValue({ data: [] })
  // 既定は枠テンプレ・定期予約不可とも0件（badge検証テストのみ個別に上書きする）。
  mockGetSlotTemplates.mockResolvedValue({ data: { templates: [], meta: { totalTemplates: 0, limit: 500 } } })
  mockListRecurringBlockedTimes.mockResolvedValue({ data: [] })

  // 既定は非管理者（MEMBER）。admin タブ構成を検証するテストのみ個別に上書きする。
  roleOverride.isAdmin = false
  roleOverride.isAdminOrDeputy = false
  roleOverride.roleName = 'MEMBER'
})

describe('TeamReservationsPanel.vue 予約直後の再読込結線', () => {
  it('AC-1/2: ReservationForm の reserved emit で枠(SlotPicker)・一覧(ReservationList)が再読込される', async () => {
    // F03.4.4 で既定タブが matrix へ変わったため、本 AC は SlotPicker 固有の結線検証を
    // 継続するために表示選好を明示的に 'list' へ切り替える。
    localStorage.setItem('mannschaft.reservation.bookDisplayMode', 'list')
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // mount 直後の初回読込回数（SlotPicker は selectedLineId 確定時の watch 発火分を含みうるため
    // 絶対値ではなく「emit 前後の差分」で判定する）。
    const slotsCallsBefore = mockGetSlots.mock.calls.length
    const listCallsBefore = mockListMyReservations.mock.calls.length
    expect(slotsCallsBefore).toBeGreaterThan(0)
    expect(listCallsBefore).toBeGreaterThan(0)

    // ReservationForm は常時 DOM 上に存在する（v-model:visible で開閉するのみ）。
    // 実際のダイアログ操作（Teleport 経由）を介さず、結線対象のイベントを直接発火して
    // 「reserved を受けたら再読込が走る」という結線契約そのものを検証する。
    const form = wrapper.findComponent(ReservationForm)
    expect(form.exists()).toBe(true)
    await form.vm.$emit('reserved')
    await flushPromises()

    // emit 後にそれぞれ最低1回追加で呼ばれていること = @reserved が正しく結線され
    // 枠(SlotPicker)・一覧(ReservationList)の再読込がトリガーされたことの証跡。
    expect(mockGetSlots.mock.calls.length).toBeGreaterThan(slotsCallsBefore)
    expect(mockListMyReservations.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })

  it('AC-3（F03.4.4）: 表示選好が未設定なら既定タブは SlotMatrixPicker（マトリックス）で、grid/list はマウントされない', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    expect(wrapper.findComponent(SlotPicker).exists()).toBe(false)
    expect(wrapper.findComponent(SlotGridPicker).exists()).toBe(false)
    // マトリックスは axis=LINE のレンジ呼びでグリッドAPIを叩く（機能C の date 単日呼びとは別経路）
    expect(mockGetSlotGrid).toHaveBeenCalled()
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

  it('AC-5: ReservationList の changed emit で枠(SlotPicker)の再読込が発火する（一覧→枠表示の逆方向結線）', async () => {
    // AC-1/2 と同じ理由で SlotPicker を実マウントさせる（既定は matrix のため list へ切替）。
    localStorage.setItem('mannschaft.reservation.bookDisplayMode', 'list')
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const slotsCallsBefore = mockGetSlots.mock.calls.length
    expect(slotsCallsBefore).toBeGreaterThan(0)

    // ReservationList は TabPanels 非 lazy のため一覧タブが非アクティブでも実マウント済み。
    const list = wrapper.findComponent(ReservationList)
    expect(list.exists()).toBe(true)
    await list.vm.$emit('changed')
    await flushPromises()

    expect(mockGetSlots.mock.calls.length).toBeGreaterThan(slotsCallsBefore)
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

  it('AC-8（UX改善5点の3）: 表示切替（マトリックス→リスト→マトリックス）で KeepAlive によりコンポーネントが破棄されず、復帰時はサイレント再取得のみ走る', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    const matrixGridCallsBefore = mockGetSlotGrid.mock.calls.length
    // getMenus は SlotMatrixPicker の onMounted のみが呼ぶ → 再mountの検出器として使う
    const menusCallsBefore = mockGetMenus.mock.calls.length
    expect(matrixGridCallsBefore).toBeGreaterThan(0)
    expect(menusCallsBefore).toBeGreaterThan(0)

    // マトリックス → リストへ切替（v-model の SelectButton 経由ではなく直接 ref を操作して検証する）
    const selectButton = wrapper.findComponent({ name: 'SelectButton' })
    expect(selectButton.exists()).toBe(true)
    await selectButton.vm.$emit('update:modelValue', 'list')
    await flushPromises()

    expect(wrapper.findComponent(SlotPicker).exists()).toBe(true)
    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(false)

    // リスト → マトリックスへ戻す。
    await selectButton.vm.$emit('update:modelValue', 'matrix')
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    // 破棄されていない証跡: 再mountなら onMounted の loadMenus が再実行されるはずだが、増えていない
    expect(mockGetMenus.mock.calls.length).toBe(menusCallsBefore)
    // 復帰時のデータ鮮度確保: onActivated のサイレント再取得（loadGrid silent）がちょうど1回走る
    expect(mockGetSlotGrid.mock.calls.length).toBe(matrixGridCallsBefore + 1)
    // サイレント＝skeleton へ切り替わらない（loading を立てない）ため、マトリックス本体は表示されたまま
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
