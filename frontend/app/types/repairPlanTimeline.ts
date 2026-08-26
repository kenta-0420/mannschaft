export interface RepairPlanTimelineResponse {
  scopeType: string
  scopeId: string
  yearFrom: number
  yearTo: number
  labels: number[]
  categories: string[]
  amountByYearAndCategory: Record<string, Record<string, number>>
  totalByYear: Record<string, number>
  chairpersonByYear: Record<string, string>
  cpiTrendByYear: Record<string, number>
}

export type TimelineLayer = 'amount' | 'chairperson' | 'cpi' | 'minutes'
