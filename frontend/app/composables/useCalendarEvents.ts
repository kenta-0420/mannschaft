export interface CalendarEventItem {
  id: number
  title: string
  startAt: string
  endAt: string
  allDay: boolean
  color: string | null
  isPersonal: boolean
  scopeType?: string
  scopeName?: string | null
  scopeIconUrl?: string | null
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

  const now = new Date()
  const currentYear = ref(now.getFullYear())
  const currentMonth = ref(now.getMonth() + 1)
  const allEvents = ref<CalendarEventItem[]>([])
  const loading = ref(true)
  const cacheFrom = ref<{ year: number; month: number } | null>(null)
  const cacheTo = ref<{ year: number; month: number } | null>(null)

  const pad = (n: number) => String(n).padStart(2, '0')

  function buildMonthRange(year: number, month: number): { from: string; to: string } {
    const lastDay = new Date(year, month, 0).getDate()
    return {
      from: `${year}-${pad(month)}-01T00:00:00`,
      to: `${year}-${pad(month)}-${pad(lastDay)}T23:59:59`,
    }
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
    const { from, to } = buildMonthRange(currentYear.value, currentMonth.value)
    return allEvents.value.filter((e) => e.startAt >= from && e.startAt <= to)
  })

  async function fetchAndCache(centerYear: number, centerMonth: number): Promise<void> {
    let from: string
    let to: string
    let start: { year: number; month: number }
    let end: { year: number; month: number }

    if (cacheHalfMonths === 0) {
      const range = buildMonthRange(centerYear, centerMonth)
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

  function navigate(delta: number): void {
    const next = addMonths(currentYear.value, currentMonth.value, delta)
    currentYear.value = next.year
    currentMonth.value = next.month

    if (cacheHalfMonths === 0 || !isWithinCache(currentYear.value, currentMonth.value)) {
      fetchAndCache(currentYear.value, currentMonth.value).catch((error) => {
        onError?.(error)
      })
    }
  }

  function onPrevMonth(): void {
    navigate(-1)
  }

  function onNextMonth(): void {
    navigate(1)
  }

  return {
    currentYear,
    currentMonth,
    events,
    loading,
    loadEvents,
    refresh,
    onPrevMonth,
    onNextMonth,
  }
}

