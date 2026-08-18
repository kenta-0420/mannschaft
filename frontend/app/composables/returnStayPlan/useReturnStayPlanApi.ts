export type ReturnStayPlanType = 'HOMECOMING' | 'STAYING'
export type ReturnStayPlanStatus = 'UPCOMING' | 'ACTIVE' | 'ENDED'

export interface ReturnStayLocation {
  countryCode: string
  prefectureCode: string | null
  regionName: string | null
}

/** 本人の予定。本人向け API は status を返さないため、状態は日付から算出する。 */
export interface OwnReturnStayPlan {
  id: string
  planType: ReturnStayPlanType
  isPublished: boolean
  location: ReturnStayLocation
  timezone: string
  startDate: string
  endDate: string
  teamIds: number[]
  version: number
  createdAt?: string
  updatedAt?: string
}

/** チーム向けの予定。status はチーム API が返す値をそのまま利用する。 */
export interface TeamReturnStayPlan {
  id: string
  ownerDisplayName: string
  ownerAvatarUrl: string | null
  planType: ReturnStayPlanType
  location: ReturnStayLocation
  timezone: string
  startDate: string
  endDate: string
  status: Exclude<ReturnStayPlanStatus, 'ENDED'>
}

/** 旧 import との互換用。新規コードでは OwnReturnStayPlan を使う。 */
export type ReturnStayPlan = OwnReturnStayPlan

export interface ReturnStayPlanRequest {
  planType: ReturnStayPlanType
  isPublished: boolean
  location: ReturnStayLocation
  startDate: string
  endDate: string
  teamIds: number[]
}

interface ApiEnvelope<T> { data: T }
interface Page<T> {
  data: T[]
  meta: { total: number; page: number; size: number; totalPages: number }
}

export function todayInTimeZone(timeZone: string, now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function todayInJapan(now = new Date()): string {
  return todayInTimeZone('Asia/Tokyo', now)
}

export function getOwnReturnStayPlanStatus(
  plan: Pick<OwnReturnStayPlan, 'startDate' | 'endDate' | 'timezone'>,
  today = todayInTimeZone(plan.timezone),
): ReturnStayPlanStatus {
  if (today < plan.startDate) return 'UPCOMING'
  if (today <= plan.endDate) return 'ACTIVE'
  return 'ENDED'
}

export function useReturnStayPlanApi() {
  const api = useApi()
  const list = (includeEnded = false) => api<Page<OwnReturnStayPlan>>(`/api/v1/me/return-stay-plans?includeEnded=${includeEnded}`)
  const create = (body: ReturnStayPlanRequest) => api<ApiEnvelope<OwnReturnStayPlan>>('/api/v1/me/return-stay-plans', { method: 'POST', body })
  const update = (id: string, version: number, body: ReturnStayPlanRequest) => api<ApiEnvelope<OwnReturnStayPlan>>(`/api/v1/me/return-stay-plans/${id}?version=${version}`, { method: 'PUT', body })
  const remove = (id: string) => api(`/api/v1/me/return-stay-plans/${id}`, { method: 'DELETE' })
  return { list, create, update, remove }
}
