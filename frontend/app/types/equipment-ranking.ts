export interface EquipmentTrendingItem {
  rank: number
  itemName: string
  category: string | null
  teamCount: number
  consumeEventCount: number
  amazonAsin: string | null
  replenishUrl: string | null
}

export interface EquipmentTrendingResponse {
  teamTemplate: string
  category: string | null  // null = 全カテゴリ横断
  optOut: boolean
  ranking: EquipmentTrendingItem[]
  totalTemplatesTeams: number
  calculatedAt: string
}

export interface EquipmentTrendingParams {
  category?: string
  limit?: number
  linkedOnly?: boolean
}

export interface EquipmentRankingOptOutResponse {
  teamId: number
  optOut: boolean
}
