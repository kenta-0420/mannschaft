export interface AnniversaryResponse {
  id: number
  teamId: number
  name: string
  date: string
  repeatAnnually: boolean
  notifyDaysBefore: number | null
  createdBy: number
  createdAt: string
}

export interface AnniversaryRequest {
  name?: string
  date: string
  repeatAnnually?: boolean
  notifyDaysBefore?: number
}
