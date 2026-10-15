export type MetricType = 'NUMBER' | 'TIME' | 'PERCENTAGE' | 'RATING'
export type AggregationType = 'SUM' | 'AVERAGE' | 'MAX' | 'MIN' | 'COUNT'

export interface MetricDefinitionDto {
  name: string
  unit: string | null
  dataType: string
  aggregationType: string
  description: string | null
  groupName: string | null
}
export interface MetricValueRangeDto {
  targetValue: number | null
  minValue: number | null
  maxValue: number | null
}
export interface MetricAccessDto {
  isVisibleToMembers: boolean
  isSelfRecordable: boolean
  linkedActivityFieldId: number | null
  isActive: boolean
}
export interface MetricAuditDto {
  createdAt: string
  updatedAt: string
}

export interface PerformanceMetric {
  id: number
  definition: MetricDefinitionDto
  valueRange: MetricValueRangeDto
  access: MetricAccessDto
  sortOrder: number
  audit: MetricAuditDto
}

export interface RecordMetricDto {
  metricId: number
  metricName: string
}
export interface RecordActorDto {
  userId: number
  scheduleId: number | null
  activityResultId: number | null
}
export interface RecordValueDto {
  recordedDate: string
  value: number
  unit: string | null
  note: string | null
}
export interface RecordSourceDto {
  source: string
  recordedBy: number | null
}
export interface RecordAuditDto {
  createdAt: string
  updatedAt: string
}

export interface PerformanceRecord {
  id: number
  metric: RecordMetricDto
  actor: RecordActorDto
  record: RecordValueDto
  source: RecordSourceDto
  audit: RecordAuditDto
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
