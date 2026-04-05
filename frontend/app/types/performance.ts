export type MetricType = 'NUMBER' | 'TIME' | 'PERCENTAGE' | 'RATING'
export type AggregationType = 'SUM' | 'AVERAGE' | 'MAX' | 'MIN' | 'COUNT'

export interface PerformanceMetric {
  id: number
  teamId: number
  name: string
  description: string | null
  unit: string | null
  metricType: MetricType
  aggregationType: AggregationType
  sortOrder: number
  isActive: boolean
  createdAt: string
}

export interface PerformanceRecord {
  id: number
  teamId: number
  userId: number
  user: { id: number; displayName: string; avatarUrl: string | null }
  metricId: number
  metricName: string
  value: number
  recordDate: string
  scheduleId: number | null
  activityId: number | null
  note: string | null
  recordedBy: { id: number; displayName: string } | null
  createdAt: string
}

export interface PerformanceStats {
  metricId: number
  metricName: string
  unit: string | null
  teamAverage: number
  teamBest: number
  totalRecords: number
  trend: Array<{ date: string; value: number }>
}

export interface MemberPerformance {
  userId: number
  displayName: string
  metrics: Array<{
    metricId: number
    metricName: string
    value: number
    rank: number
    percentile: number
    trend: 'UP' | 'DOWN' | 'STABLE'
  }>
}
