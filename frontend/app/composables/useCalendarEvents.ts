import dayjs from 'dayjs'

export interface CalendarEventItem {
  id: number
  /**
   * 親 {@code schedules} 行の ID（BE {@code CalendarEntryResponse.scheduleId}・設計書 §1.5 / AC-07(b)）。
   *
   * schedule 由来のエントリでは {@code id} と同値。reflection 等 UUID 主キードメイン由来（{@code id=-1}）や
   * TODO（{@code id}負数）では常に {@code null}。イベント詳細のコメントセクション表示可否判定に使う。
   */
  scheduleId?: number | null
  /**
   * 一覧/ループの安定一意キー（v-for :key・ルックアップ用）。
   *
   * 既存の数値 id 依存だと、UUID 主キードメイン（reflection・F06.5 §6.2/AC-21）の行は
   * id=null で衝突する。よって全イベントに文字列の uniqueKey を持たせ、:key とルックアップは
   * これを使う。schedule 行は `String(id)`、reflection 行は `ref:{referenceUuid}`。
   */
  uniqueKey: string
  title: string
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  isPersonal: boolean
  isTodo?: boolean
  /** reflection 等 UUID 主キードメインのカレンダー印か（id 非依存描画・§6.2/AC-21）。 */
  isReflection?: boolean
  /** reflection 行の参照 UUID（entry または theme・referenceKind で意味が変わる）。 */
  referenceUuid?: string | null
  /** reflection 行の参照種別（"REFLECTION_ENTRY" / "REFLECTION_RECALL"）。 */
  referenceKind?: string | null
  eventType?: string
  scopeType?: string
  scopeName?: string | null
  scopeIconUrl?: string | null
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Array<{
    userId: number
    displayName: string
    avatarUrl: string | null
    calendarColor: string | null
  }>
  /**
   * 出欠回答が必須のイベントか（モバイルのリストビューで行内 RSVP ボタンの出し分けに使う）。
   * BE ScheduleResponse.content.attendanceRequired 由来。未設定は false 扱い。
   */
  attendanceRequired?: boolean
  /**
   * 閲覧者本人の出欠回答状態（'YES' / 'NO' / 'MAYBE' / null）。
   * 一覧 API は null を返し、詳細 GET で実値が入る（BE 現仕様）。
   */
  myAttendance?: string | null
}

export interface UseCalendarEventsOptions {
  cacheHalfMonths?: number
  onError?: (error: unknown) => void
}

export function useCalendarEvents(
  fetcher: (from: string, to: string) => Promise<CalendarEventItem[]>,
  options: UseCalendarEventsOptions = {},
) {
  const { cacheHalfMonths = 2, onError } = options
  const { buildDayStartStr, buildDayEndStr } = useDatetime()

  const now = new Date()
  const currentYear = ref(now.getFullYear())
  const currentMonth = ref(now.getMonth() + 1)
  const allEvents = ref<CalendarEventItem[]>([])
  const loading = ref(true)
  const calendarLoading = ref(false)
  const cacheFrom = ref<{ year: number; month: number } | null>(null)
  const cacheTo = ref<{ year: number; month: number } | null>(null)

  const pad = (n: number) => String(n).padStart(2, '0')

  /**
   * 対象月の「ユーザーTZでの月初 00:00:00 〜 月末 23:59:59」をオフセット付き文字列で返す。
   *
   * 以前はオフセット無しのナイーブ文字列を送っていたため、BE 側でサーバー既定TZの壁時計として
   * 解釈され、ユーザーTZが JST 以外の場合に取得範囲がずれていた（Issue #2508）。
   * BE が オフセット付き `LocalDateTime` を受理できるようになったため、明示的に付与する。
   */
  function buildMonthRange(year: number, month: number): { from: string; to: string } {
    const lastDay = new Date(year, month, 0).getDate()
    return {
      from: buildDayStartStr(`${year}-${pad(month)}-01`),
      to: buildDayEndStr(`${year}-${pad(month)}-${pad(lastDay)}`),
    }
  }

  /** 月表示グリッド（6週=42セル）の範囲をユーザーTZ基準のオフセット付き文字列で返す。 */
  function buildGridRange(year: number, month: number): { from: string; to: string } {
    const first = new Date(year, month - 1, 1)
    const startOffset = first.getDay() // 0=日曜
    const gridStart = new Date(year, month - 1, 1 - startOffset)
    const gridEnd = new Date(year, month - 1, 1 - startOffset + 41) // 42セル=6週
    const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    return { from: buildDayStartStr(fmt(gridStart)), to: buildDayEndStr(fmt(gridEnd)) }
  }

  function addMonths(year: number, month: number, delta: number): { year: number; month: number } {
    const d = new Date(year, month - 1 + delta, 1)
    return { year: d.getFullYear(), month: d.getMonth() + 1 }
  }

  function isWithinCache(year: number, month: number): boolean {
    if (!cacheFrom.value || !cacheTo.value) return false
    const val = year * 12 + month
    const from = cacheFrom.value.year * 12 + cacheFrom.value.month
    const to = cacheTo.value.year * 12 + cacheTo.value.month
    return val >= from && val <= to
  }

  const events = computed<CalendarEventItem[]>(() => {
    const { from, to } = buildGridRange(currentYear.value, currentMonth.value)
    // from/to はオフセット付きになったため、文字列の辞書順比較では意味が壊れる（BE 応答と
    // オフセットが異なりうる）。瞬間（epoch ミリ秒）に落として比較する。
    const fromMs = Date.parse(from)
    const toMs = Date.parse(to)
    return allEvents.value.filter((e) => {
      const at = Date.parse(e.startAt)
      return at >= fromMs && at <= toMs
    })
  })

  async function fetchAndCache(centerYear: number, centerMonth: number): Promise<void> {
    let from: string
    let to: string
    let start: { year: number; month: number }
    let end: { year: number; month: number }

    if (cacheHalfMonths === 0) {
      const range = buildGridRange(centerYear, centerMonth)
      from = range.from
      to = range.to
      start = { year: centerYear, month: centerMonth }
      end = { year: centerYear, month: centerMonth }
    } else {
      start = addMonths(centerYear, centerMonth, -cacheHalfMonths)
      end = addMonths(centerYear, centerMonth, cacheHalfMonths)
      from = buildMonthRange(start.year, start.month).from
      to = buildMonthRange(end.year, end.month).to
    }

    const fetched = await fetcher(from, to)
    allEvents.value = fetched
    cacheFrom.value = start
    cacheTo.value = end
  }

  async function loadEvents(): Promise<void> {
    loading.value = true
    try {
      await fetchAndCache(currentYear.value, currentMonth.value)
    } catch (error) {
      onError?.(error)
      allEvents.value = []
    } finally {
      loading.value = false
    }
  }

  async function refresh(): Promise<void> {
    try {
      await fetchAndCache(currentYear.value, currentMonth.value)
    } catch (error) {
      onError?.(error)
    }
  }

  /**
   * `refresh()` と同じ再取得を行い、**この呼び出しの成否を戻り値で返す**。
   *
   * `refresh()` は失敗しても正常に解決する（月移動で画面が落ちないための設計であり、
   * 既存の呼び出し元がその挙動に依存しているため変えない）。そのため「自分が投げた
   * 再取得が成功したか」を知りたい呼び出し元は本関数を使う。
   *
   * **成否は呼び出しごとに閉じている**（共有フラグを見ない）。`ref` に成否を書いて
   * 後から読む形だと、月移動など並行する別の取得の結果で上書きされ、取り違える。
   */
  async function refreshWithResult(): Promise<{ ok: boolean; error?: unknown }> {
    try {
      await fetchAndCache(currentYear.value, currentMonth.value)
      return { ok: true }
    } catch (error) {
      onError?.(error)
      return { ok: false, error }
    }
  }

  /** 表示中の年月を任意の年月へ直接移動する（キャッシュ範囲外なら再取得）。 */
  function navigateTo(year: number, month: number): void {
    currentYear.value = year
    currentMonth.value = month

    if (cacheHalfMonths === 0 || !isWithinCache(currentYear.value, currentMonth.value)) {
      calendarLoading.value = true
      fetchAndCache(currentYear.value, currentMonth.value)
        .catch((error) => { onError?.(error) })
        .finally(() => { calendarLoading.value = false })
    }
  }

  function navigate(delta: number): void {
    const next = addMonths(currentYear.value, currentMonth.value, delta)
    navigateTo(next.year, next.month)
  }

  function onPrevMonth(): void {
    navigate(-1)
  }

  function onNextMonth(): void {
    navigate(1)
  }

  /**
   * 「今日」ボタン（§6.3・AC-12d）: ユーザータイムゾーン基準の今日が属する月へ移動する。
   * 既に当月表示中の場合は再取得せず何もしない（呼び出し側がフォーカス移動のみ行う）。
   */
  function goToToday(): void {
    const { userTimezone } = useDatetime()
    const now = dayjs().tz(userTimezone.value)
    const year = now.year()
    const month = now.month() + 1
    if (year === currentYear.value && month === currentMonth.value) return
    navigateTo(year, month)
  }

  return {
    currentYear,
    currentMonth,
    events,
    loading,
    calendarLoading,
    loadEvents,
    refresh,
    refreshWithResult,
    onPrevMonth,
    onNextMonth,
    goToToday,
    // F03.19 §6.5.3: 週ビューは表示中の週が月をまたぐことがあり、その週を包含する月へ
    // 取得範囲を寄せるために任意の年月へ直接移動できる必要がある。
    navigateTo,
  }
}
