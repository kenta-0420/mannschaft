import { describe, it, expect, vi, afterAll, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import dayjs from 'dayjs'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'

/**
 * SlotMatrixPicker.vue（F03.4.4 マトリックスUI・機能H）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: from/to のレンジ呼びでグリッドAPIを叩く（機能C の date 単日呼びとは別経路）
 *   AC-2: 予約対象ゼロは対象作成の空状態を表示する
 *   AC-3: 30分セル（span=1・AVAILABLE）クリックでメニュー選択ダイアログが開く（GroupBookingDialog）
 *   AC-4: 長尺手動枠（span>1・colspan跨ぎ描画）クリックは slotSelected を emit する（グループダイアログを開かない）
 *   AC-5: モバイル規約: 縦横スクロールコンテナに overscroll-contain が付与される（UX改善5点の4で縦横統合）
 *   AC-6: 時間ヘッダ行 sticky top・左上交差セル両軸 sticky（UX改善5点の4・マトリックス時間ヘッダsticky化）
 *   AC-8（検分是正・2026-07-30）: 週を移動してグリッドを再取得したあとも、その週の
 *        登録済み枠が「待機中」として認識される（`loadMyWaitlist` の呼び忘れで週移動後に
 *        「待機中」表示が消え、登録ボタンを押すと409になる実バグの回帰防止）
 *
 * 注: テスト環境の既定ロケールは en。日付依存の flaky を避けるため、返す日は常に「明日」にする
 *     （isPastCell の過去判定に一切かからない・実行時刻に依存しない）。
 *
 * 時計固定について（TEST_CONVENTION.md §2.4.1）: コンポーネントは
 * `dayjs().tz(userTimezone.value)`（Asia/Tokyo 明示変換）で isPastCell を判定する一方、
 * 素の `dayjs()` はプロセス TZ で評価される。CI は TZ=UTC で走るため、両者は
 * UTC 15:00〜24:00（= JST 翌日 00:00〜09:00）の窓で暦日がずれ得る
 * （ScheduleExceptionPanel.spec.ts が実際に踏んだ構図と同一）。相対化だけでは解決しないため、
 * 時計そのものを固定する。
 */
const mockGetLines = vi.fn()
const mockGetMenus = vi.fn()
const mockGetSlotGrid = vi.fn()
const mockGetReservationSettings = vi.fn()
const mockListMyWaitlist = vi.fn()
const mockCreateGroup = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getLines: mockGetLines,
    getMenus: mockGetMenus,
    getSlotGrid: mockGetSlotGrid,
    getReservationSettings: mockGetReservationSettings,
    listMyWaitlist: mockListMyWaitlist,
    createGroup: mockCreateGroup,
  }),
}))

mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

// 時計固定（TEST_CONVENTION.md §2.4.1）: UTC 03:00 = JST 12:00。
// どちらの TZ で評価しても同じ暦日になる「安全な昼間」を選ぶ（境界近くは無意味）。
// `tomorrow` を計算する前に固定する必要があるため、モジュール読み込み時点（トップレベル）で設定する。
vi.useFakeTimers({ toFake: ['Date'] })
vi.setSystemTime(new Date('2026-08-11T03:00:00Z'))

const activeLine = { id: 1, meta: { name: 'Seat1', isActive: true } }
const tomorrow = dayjs().add(1, 'day').format('YYYY-MM-DD')

function gridResponseWithCells() {
  return {
    data: {
      meta: null,
      days: [
        {
          date: tomorrow,
          columns: [
            {
              lineId: 1,
              lineName: 'Seat1',
              lineIds: [],
              cells: [
                { slotId: 201, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
                { slotId: 202, startTime: '10:30', endTime: '11:30', state: 'AVAILABLE' }, // 60分・span=2
              ],
            },
            { lineId: null, lineName: null, lineIds: [], cells: [] },
          ],
        },
      ],
    },
  }
}

/** 満席（BOOKED）セル1枠のみを持つグリッド応答を作る（週移動テスト用）。 */
function bookedGridResponse(slotId: number, date: string) {
  return {
    data: {
      meta: null,
      days: [
        {
          date,
          columns: [
            {
              lineId: 1,
              lineName: 'Seat1',
              lineIds: [],
              cells: [
                { slotId, startTime: '10:00', endTime: '10:30', state: 'BOOKED' },
              ],
            },
            { lineId: null, lineName: null, lineIds: [], cells: [] },
          ],
        },
      ],
    },
  }
}

async function flushRaw() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

/** 既存のセル操作テストは明示的に全日を開いた状態を前提にする。初期全閉の契約は専用テストで raw flush を使う。 */
async function findDateToggle(wrapper: VueWrapper, date: string): Promise<HTMLButtonElement | null> {
  for (let attempt = 0; attempt < 20; attempt++) {
    const toggle = wrapper.find<HTMLButtonElement>(`[data-testid="matrix-toggle-date-${date}"]`)
    if (toggle.exists()) return toggle.element
    await flushRaw()
  }
  return null
}

/** 初期全閉のグリッドで、検査対象の日だけを開く。 */
async function openDate(wrapper: VueWrapper, date: string): Promise<void> {
  const toggle = await findDateToggle(wrapper, date)
  if (!toggle || toggle.getAttribute('aria-expanded') === 'true') return
  toggle.click()
  await flushRaw()
}

/** 通常のセル検査用に対象日を開いてから描画を安定させる。 */
async function flush(wrapper: VueWrapper, date = tomorrow) {
  await flushRaw()
  await openDate(wrapper, date)
}

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

beforeEach(() => {
  mockGetLines.mockReset()
  mockGetMenus.mockReset()
  mockGetSlotGrid.mockReset()
  mockGetReservationSettings.mockReset()
  mockListMyWaitlist.mockReset()
  mockCreateGroup.mockReset()
  mockGetMenus.mockResolvedValue({ data: [] })
  mockGetReservationSettings.mockResolvedValue({ data: { resourceNameType: 'DEFAULT', resourceNameCustom: null } })
  mockListMyWaitlist.mockResolvedValue({ data: [] })
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

afterAll(() => {
  // 他スペックへの汚染を防ぐため、必ず実時計に戻す（TEST_CONVENTION.md §2.4.1）。
  vi.useRealTimers()
})

describe('SlotMatrixPicker.vue', () => {
  it('AC-17: 初期は全日閉じ、日別開閉と全体開閉が機能し、週移動後も曜日ごとの開閉状態を保つ', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flushRaw()

    expect(findByTestId('matrix-no-slots-empty')).toBeNull()
    expect(wrapper.findAll('[data-header-index]').length).toBe(0)
    const dayToggle = await findDateToggle(wrapper, tomorrow)
    expect(dayToggle?.getAttribute('aria-expanded')).toBe('false')

    await openDate(wrapper, tomorrow)
    expect((await findDateToggle(wrapper, tomorrow))?.getAttribute('aria-expanded')).toBe('true')
    expect(wrapper.findAll('[data-header-index]').length).toBeGreaterThan(0)

    const nextWeekDate = dayjs(tomorrow).add(7, 'day').format('YYYY-MM-DD')
    mockGetSlotGrid.mockResolvedValue(bookedGridResponse(201, nextWeekDate))
    const nextWeekBtn = wrapper.findAllComponents({ name: 'Button' }).find(b => b.props('icon') === 'pi pi-angle-right')
    await nextWeekBtn!.trigger('click')
    await flush(wrapper, nextWeekDate)
    expect((await findDateToggle(wrapper, nextWeekDate))?.getAttribute('aria-expanded')).toBe('true')

    wrapper.find<HTMLButtonElement>('[data-testid="matrix-toggle-all"]').element.click()
    await flushRaw()
    expect(wrapper.findAll('[data-header-index]').length).toBe(0)
  })

  it('AC-18: 自分の予約済み満席セルは予約済み表示かつ操作不能で、キャンセル待ちを開かない', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue({
      data: {
        meta: null,
        days: [{
          date: tomorrow,
          columns: [{
            lineId: 1,
            lineName: 'Seat1',
            lineIds: [],
            cells: [{ slotId: 901, startTime: '10:00', endTime: '10:30', state: 'BOOKED', reservedByCurrentUser: true }],
          }],
        }],
      },
    })
    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper, tomorrow)

    const cell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('10:00'))
    expect(cell?.text()).toContain('Booked by you')
    expect(cell?.attributes('disabled')).toBeDefined()
    await cell!.trigger('click')
    await flush(wrapper)
    expect(findByTestId('waitlist-register')).toBeNull()
  })

  it('チームTZをviewer TZより優先し、週起点・API範囲・過去セルをチーム日付境界で判定する', async () => {
    // UTC 23:30 は viewer=Asia/Tokyo では翌日08:30だが、team=America/New_Yorkでは同日19:30。
    vi.setSystemTime(new Date('2026-08-09T23:30:00Z'))
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue({
      data: {
        meta: null,
        days: [{
          date: '2026-08-09',
          columns: [{
            lineId: 1,
            lineName: 'Seat1',
            lineIds: [],
            cells: [{ slotId: 901, slotDate: '2026-08-09', endDate: '2026-08-09', startTime: '20:00', endTime: '20:30', state: 'AVAILABLE' }],
          }],
        }],
      },
    })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', teamTimezone: 'America/New_York', isAdmin: false },
    })
    await flush(wrapper)

    const [, params] = mockGetSlotGrid.mock.calls[0] as [string, Record<string, unknown>]
    // viewer TZ基準なら 2026-08-10週になるが、team TZでは 2026-08-03週。
    expect(params.from).toBe('2026-08-03')
    expect(params.to).toBe('2026-08-09')
    const cell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('20:00'))
    expect(cell?.attributes('disabled')).toBeUndefined()

    vi.setSystemTime(new Date('2026-08-11T03:00:00Z'))
  })

  it('AC-1: from/to レンジ呼びでグリッドAPIを叩く（#2575 で axis は送らない）', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    expect(mockGetSlotGrid).toHaveBeenCalled()
    const [teamId, params] = mockGetSlotGrid.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    // #2575: 撤去済みの axis / staffUserIds は一切送らない。
    expect(params.axis).toBeUndefined()
    expect(params.staffUserIds).toBeUndefined()
    expect(params.from).toBeTruthy()
    expect(params.to).toBeTruthy()
    expect(params.date).toBeUndefined()
  })

  it('AC-2: 予約対象ゼロは対象作成の空状態を表示する', async () => {
    mockGetLines.mockResolvedValue({ data: [] })
    mockGetSlotGrid.mockResolvedValue({ data: { days: [] } })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: true },
    })
    await flush(wrapper)

    expect(wrapper.html()).toContain('No reservation targets yet')
  })

  it('AC-3: 30分セル（span=1・AVAILABLE）クリックでメニュー選択ダイアログが開く', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    // 10:00 の30分セル（span=1）をクリック
    const cell10 = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('10:00'))
    expect(cell10).toBeTruthy()
    await cell10!.trigger('click')
    await flush(wrapper)

    // GroupBookingDialog が開き「メニューなしで30分だけ予約」ボタンが描画される
    expect(findByTestId('group-no-menu')).not.toBeNull()
    expect(wrapper.emitted('slotSelected')).toBeFalsy()
  })

  it('AC-4: 長尺手動枠（span>1）クリックは slotSelected を emit し、グループダイアログを開かない', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    // 10:30-11:30（60分・span=2）セルをクリック
    const longCell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('10:30'))
    expect(longCell).toBeTruthy()
    await longCell!.trigger('click')
    await flush(wrapper)

    expect(wrapper.emitted('slotSelected')).toBeTruthy()
    const payload = wrapper.emitted('slotSelected')![0]
    expect(payload).toEqual([202, 1, 'Seat1', tomorrow, '10:30', '11:30'])
    // 単枠フローは親のReservationFormへ委譲するため、グループダイアログ内の要素は出ない
    expect(findByTestId('group-no-menu')).toBeNull()
  })

  it('AC-16: 実際の slotDate が未来なら翌日終了セルを disabled にせず、終了時刻を明示する', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    const rowDate = dayjs().format('YYYY-MM-DD')
    const endDate = dayjs(tomorrow).add(1, 'day').format('YYYY-MM-DD')
    mockGetSlotGrid.mockResolvedValue({
      data: {
        meta: null,
        days: [{
          date: rowDate,
          columns: [{
            lineId: 1,
            lineName: 'Seat1',
            lineIds: [],
            cells: [{ slotId: 299, slotDate: tomorrow, endDate, startTime: '23:00', endTime: '04:00', state: 'AVAILABLE' }],
          }],
        }],
      },
    })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper, rowDate)

    const cell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('23:00'))
    expect(cell).toBeTruthy()
    expect(cell!.attributes('disabled')).toBeUndefined()
    expect(cell!.attributes('aria-label')).toContain('Next day 04:00')
  })

  it('AC-5: 縦横スクロールコンテナに overscroll-contain が付与される（縦→横ホイール変換は実装しない。UX改善5点の4で縦スクロールも同一コンテナに統合）', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    expect(wrapper.html()).toContain('overscroll-contain')
  })

  it('AC-6（UX改善5点の4）: 時間ヘッダ行が sticky top、左上の交差セルが両軸 sticky で higher z-index を持つ', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    const html = wrapper.html()
    // 左上コーナー: 左右上下の両軸 sticky（left-0 と top-0 の両方）で最前面（z-20）
    expect(html).toMatch(/sticky left-0 top-0 z-20/)
    // 時間ヘッダセル: 上方向 sticky（top-0）
    expect(html).toMatch(/sticky top-0 z-10/)
    // 左の行ヘッダ列（日付×予約対象）は既存どおり left-0 sticky を維持
    expect(html).toMatch(/sticky left-0 z-10/)
  })

  it('AC-7（F03.4.5 §4.4）: UNAVAILABLE セルに unavailableReason があれば「×事由」を描画し、無ければ従来の「Unavailable」表示のまま', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue({
      data: {
        meta: null,
        days: [
          {
            date: tomorrow,
            columns: [
              {
                lineId: 1,
                lineName: 'Seat1',
                lineIds: [],
                cells: [
                  // is_public=TRUE の定期ルール由来: 事由ラベル付き
                  { slotId: 301, startTime: '19:00', endTime: '19:30', state: 'UNAVAILABLE', unavailableReason: 'Training' },
                  // 単発 blocked_times 由来・非公開ルール由来: unavailableReason は BE から届かない（null/undefined）
                  { slotId: 302, startTime: '19:30', endTime: '20:00', state: 'UNAVAILABLE' },
                ],
              },
              { lineId: null, lineName: null, lineIds: [], cells: [] },
            ],
          },
        ],
      },
    })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    // 事由ありセル: × と事由文字列を描画し、aria-label/title にも事由が載る
    const reasonCell = wrapper.findAll('button').find(b => b.text().includes('Training'))
    expect(reasonCell, '事由ラベル付きセルが描画されること').toBeTruthy()
    expect(reasonCell!.text()).toContain('×')
    expect(reasonCell!.attributes('aria-label')).toContain('Training')
    expect(reasonCell!.attributes('title')).toBe('Training')

    // 事由なしセル（従来表示）: 「Unavailable」のみ・× や余計な文言を出さない（型の嘘フォールバック禁止の裏取り）
    const plainCell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('19:30') && b.attributes('aria-label')?.includes('Unavailable'))
    expect(plainCell, '事由なしセルが従来どおり Unavailable 表示のままであること').toBeTruthy()
    expect(plainCell!.text()).toBe('Unavailable')
    expect(plainCell!.text()).not.toContain('×')
  })

  it('AC-8（検分是正）: 週を移動すると新しい週の登録済み枠が「待機中」として認識される', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    // 自分の WAITING は slotId=902（初期表示の週にはまだ現れない・翌週に現れる）のみ。
    mockListMyWaitlist.mockResolvedValue({ data: [{ id: 'w-1', teamId: 999, slotId: 902, status: 'WAITING' }] })

    // 初期表示（今週）: slotId=901（BOOKED・未登録）のみを含む。
    // 注: mockResolvedValueOnce は使わない——初期 mount 中に watch(weekStart) と onMounted の両方から
    // loadGrid が呼ばれうる（weekStart の初期セットが watch を誘発するため呼び出し回数は環境依存）。
    // persistent な mockResolvedValue にして「何回呼ばれても同じ週の応答を返す」形にし、回数に依存しない。
    mockGetSlotGrid.mockResolvedValue(bookedGridResponse(901, tomorrow))

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush(wrapper)

    // 今週の応答には登録先(902)が含まれないため「待機中」は出ない（901はBOOKEDのまま=Full表示）
    expect(wrapper.text()).not.toContain('Waiting')
    expect(wrapper.text()).toContain('Full')

    // 翌週へ移動: 以後の全呼び出しを slotId=902（BOOKED・登録済み）を含む応答に切り替える
    const nextWeekDate = dayjs(tomorrow).add(7, 'day').format('YYYY-MM-DD')
    mockGetSlotGrid.mockResolvedValue(bookedGridResponse(902, nextWeekDate))

    const nextWeekBtn = wrapper.findAllComponents({ name: 'Button' }).find(b => b.props('icon') === 'pi pi-angle-right')
    expect(nextWeekBtn, '翌週ボタンが見つかること').toBeTruthy()
    await nextWeekBtn!.trigger('click')
    await flush(wrapper, nextWeekDate)

    // loadMyWaitlist がグリッド再取得後に呼ばれ、902 が loadedSlotIds に含まれるようになったことで
    // セルの表示が「待機中」に切り替わる（呼び忘れがあれば表示されない＝このアサーションが落ちる）。
    // 凡例に常時「Full」ラベルが出るため、ページ全体に「Full」が無いことではなく
    // 「Waiting」セルが実際に出現したことで判定する。
    expect(wrapper.text()).toContain('Waiting')
  })

  // === AC-9〜AC-12: ドラッグ複数選択（機能H） ===
  //
  // ポインタ移動/離しの購読は window 上のため、必ず「セル要素から bubbles:true で
  // dispatch」して window へ伝播させる（window に直接 dispatch すると event.target が
  // window になり、実ブラウザでの挙動（target=カーソル直下の要素）と食い違う）。
  // jsdom には PointerEvent が無いため MouseEvent で代替する（pointerType は undefined
  // ＝タッチではない扱いになり、マウスドラッグと同じ経路を通る）。
  function dispatchPointer(el: Element, type: string, init: MouseEventInit = {}) {
    el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, ...init }))
  }

  /** ヘッダ列インデックスからセル要素を引く（data-header-index は実装の座標解決にも使う属性）。 */
  function cellAt(root: Element, rowIndex: number, headerIndex: number): HTMLElement {
    const el = root.querySelector<HTMLElement>(
      `[data-row-index="${rowIndex}"][data-header-index="${headerIndex}"]`,
    )
    if (!el) throw new Error(`セルが見つからない: row=${rowIndex} header=${headerIndex}`)
    return el
  }

  /** 30分の AVAILABLE セルが3枚連続する行（10:00/10:30/11:00）。 */
  function consecutiveGridResponse() {
    return {
      data: {
        meta: null,
        days: [
          {
            date: tomorrow,
            columns: [
              {
                lineId: 1,
                lineName: 'Seat1',
                lineIds: [],
                cells: [
                  { slotId: 901, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
                  { slotId: 902, startTime: '10:30', endTime: '11:00', state: 'AVAILABLE' },
                  { slotId: 903, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
                ],
              },
            ],
          },
        ],
      },
    }
  }

  it('AC-9: 連続 AVAILABLE をドラッグして離すと、一括予約プレビュー（GroupBookingDialog）へ入る', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(consecutiveGridResponse())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
      // pointermove/pointerup は window で購読するため、実 DOM に接続しないと伝播しない
      // （既定のマウントは detached で、イベントがコンポーネント root 止まりになる）。
      attachTo: document.body,
    })
    await flush(wrapper)

    const root = wrapper.element as Element
    const start = cellAt(root, 0, 0)
    const end = cellAt(root, 0, 2)

    dispatchPointer(start, 'pointerdown', { clientX: 100, clientY: 100 })
    // しきい値(8px)を超えて 11:00 のセルまで移動
    dispatchPointer(end, 'pointermove', { clientX: 200, clientY: 100 })
    await flush(wrapper)

    // ドラッグ中は選択範囲が視覚的に追従する（3枚ともハイライト）
    expect(cellAt(root, 0, 0).className).toContain('ring-primary')
    expect(cellAt(root, 0, 1).className).toContain('ring-primary')
    expect(cellAt(root, 0, 2).className).toContain('ring-primary')

    dispatchPointer(end, 'pointerup', { clientX: 200, clientY: 100 })
    await flush(wrapper)

    // メニュー選択ステップを飛ばして「連続枠プレビュー」に入る（＝確定ボタンが出る）
    expect(findByTestId('group-confirm'), '一括予約の確定ボタンが出ること').not.toBeNull()
    expect(findByTestId('group-no-menu'), 'ドラッグ経路ではメニュー選択ステップを挟まない').toBeNull()
    // 3枠ぶんが選択されている
    expect(document.body.textContent).toContain('10:00 - 11:30')
    wrapper.unmount()
  })

  it('AC-10: BOOKED をまたぐドラッグは、その手前で打ち切られる', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue({
      data: {
        meta: null,
        days: [
          {
            date: tomorrow,
            columns: [
              {
                lineId: 1,
                lineName: 'Seat1',
                lineIds: [],
                cells: [
                  { slotId: 901, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
                  { slotId: 902, startTime: '10:30', endTime: '11:00', state: 'BOOKED' },
                  { slotId: 903, startTime: '11:00', endTime: '11:30', state: 'AVAILABLE' },
                ],
              },
            ],
          },
        ],
      },
    })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
      // pointermove/pointerup は window で購読するため、実 DOM に接続しないと伝播しない
      // （既定のマウントは detached で、イベントがコンポーネント root 止まりになる）。
      attachTo: document.body,
    })
    await flush(wrapper)

    const root = wrapper.element as Element
    const start = cellAt(root, 0, 0)
    const end = cellAt(root, 0, 2)

    dispatchPointer(start, 'pointerdown', { clientX: 100, clientY: 100 })
    dispatchPointer(end, 'pointermove', { clientX: 200, clientY: 100 })
    await flush(wrapper)

    // BOOKED の手前（10:00 のみ）で打ち切られ、その先はハイライトされない
    expect(cellAt(root, 0, 0).className).toContain('ring-primary')
    expect(cellAt(root, 0, 1).className).not.toContain('ring-primary')
    expect(cellAt(root, 0, 2).className).not.toContain('ring-primary')

    dispatchPointer(end, 'pointerup', { clientX: 200, clientY: 100 })
    await flush(wrapper)

    // 選択は1枠のみ＝従来どおりメニュー選択ステップから始まる（一括予約にはならない）
    expect(findByTestId('group-no-menu')).not.toBeNull()
    wrapper.unmount()
  })

  it('AC-11: ESC でドラッグ選択を取り消せる（予約導線に入らない）', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(consecutiveGridResponse())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
      // pointermove/pointerup は window で購読するため、実 DOM に接続しないと伝播しない
      // （既定のマウントは detached で、イベントがコンポーネント root 止まりになる）。
      attachTo: document.body,
    })
    await flush(wrapper)

    const root = wrapper.element as Element
    const start = cellAt(root, 0, 0)
    const end = cellAt(root, 0, 2)

    dispatchPointer(start, 'pointerdown', { clientX: 100, clientY: 100 })
    dispatchPointer(end, 'pointermove', { clientX: 200, clientY: 100 })
    await flush(wrapper)
    expect(cellAt(root, 0, 2).className).toContain('ring-primary')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flush(wrapper)

    // ハイライトが消え、離してもダイアログは開かない
    expect(cellAt(root, 0, 0).className).not.toContain('ring-primary')
    dispatchPointer(end, 'pointerup', { clientX: 200, clientY: 100 })
    await flush(wrapper)
    expect(findByTestId('group-confirm')).toBeNull()
    expect(findByTestId('group-no-menu')).toBeNull()
    wrapper.unmount()
  })

  it('AC-12: しきい値未満の pointerdown→pointerup は従来どおりの単発クリック挙動のまま', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(consecutiveGridResponse())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
      // pointermove/pointerup は window で購読するため、実 DOM に接続しないと伝播しない
      // （既定のマウントは detached で、イベントがコンポーネント root 止まりになる）。
      attachTo: document.body,
    })
    await flush(wrapper)

    const root = wrapper.element as Element
    const start = cellAt(root, 0, 0)

    // 3px しか動かない＝ドラッグ扱いにしない
    dispatchPointer(start, 'pointerdown', { clientX: 100, clientY: 100 })
    dispatchPointer(start, 'pointermove', { clientX: 103, clientY: 100 })
    dispatchPointer(start, 'pointerup', { clientX: 103, clientY: 100 })
    await flush(wrapper)
    // ドラッグ確定していないのでこの時点ではダイアログは開かない
    expect(findByTestId('group-confirm')).toBeNull()

    // 続けて発火する click が従来どおりメニュー選択ダイアログを開く
    await wrapper.findAll('button').find(b => b.attributes('data-header-index') === '0')!.trigger('click')
    await flush(wrapper)
    expect(findByTestId('group-no-menu'), '単発クリックの既存挙動が壊れていないこと').not.toBeNull()
    wrapper.unmount()
  })

  it('AC-13: タッチ（pointerType=touch）ではドラッグ選択を開始しない（縦横パンを優先する）', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(consecutiveGridResponse())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
      // pointermove/pointerup は window で購読するため、実 DOM に接続しないと伝播しない
      // （既定のマウントは detached で、イベントがコンポーネント root 止まりになる）。
      attachTo: document.body,
    })
    await flush(wrapper)

    const root = wrapper.element as Element
    const start = cellAt(root, 0, 0)
    const end = cellAt(root, 0, 2)

    // pointerType='touch' を明示した pointerdown
    const down = new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 100, clientY: 100 })
    Object.defineProperty(down, 'pointerType', { value: 'touch' })
    start.dispatchEvent(down)
    dispatchPointer(end, 'pointermove', { clientX: 200, clientY: 100 })
    await flush(wrapper)

    // 選択ハイライトが一切出ない＝スクロール（パン）を邪魔しない
    expect(cellAt(root, 0, 0).className).not.toContain('ring-primary')
    expect(cellAt(root, 0, 2).className).not.toContain('ring-primary')

    dispatchPointer(end, 'pointerup', { clientX: 200, clientY: 100 })
    await flush(wrapper)
    expect(findByTestId('group-confirm')).toBeNull()
    wrapper.unmount()
  })

  it('日跨ぎ同一lineの23:30→翌日00:00をメニュー選択しslotIds payload化する', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetMenus.mockResolvedValue({ data: [{ id: 'overnight-menu', name: 'Overnight', durationMinutes: 60, requiredSlotCount: 2, isActive: true, lineIds: [] }] })
    mockGetSlotGrid.mockResolvedValue({
      data: { days: [
        { date: '2026-08-12', columns: [{ lineId: 1, lineName: 'Seat1', lineIds: [], cells: [{ slotId: 701, slotDate: '2026-08-12', endDate: '2026-08-13', startTime: '23:30', endTime: '00:00', state: 'AVAILABLE' }] }] },
        { date: '2026-08-13', columns: [{ lineId: 1, lineName: 'Seat1', lineIds: [], cells: [{ slotId: 702, slotDate: '2026-08-13', endDate: '2026-08-13', startTime: '00:00', endTime: '00:30', state: 'AVAILABLE' }] }] },
      ] },
    })
    mockCreateGroup.mockResolvedValue({ data: {} })

    const wrapper = await mountSuspended(SlotMatrixPicker, { props: { teamId: 'team-slug', isAdmin: false } })
    await flush(wrapper, '2026-08-12')
    const cell = wrapper.findAll('button').find(button => button.attributes('aria-label')?.includes('23:30'))
    expect(cell).toBeTruthy()
    await cell!.trigger('click')
    await flush(wrapper, '2026-08-12')
    const menu = findByTestId<HTMLButtonElement>('group-menu-option-overnight-menu')
    expect(menu).toBeTruthy()
    menu!.click()
    await flush(wrapper, '2026-08-12')
    findByTestId<HTMLButtonElement>('group-confirm')!.click()
    await flush(wrapper, '2026-08-12')

    expect(mockCreateGroup).toHaveBeenCalledWith('team-slug', expect.objectContaining({ slotIds: [701, 702] }))
  })
})
