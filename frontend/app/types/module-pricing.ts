/** モジュール価格設定レスポンス */
export interface ModulePricingResponse {
  moduleId: number
  moduleName: string
  moduleSlug: string
  monthlyPrice: number
  yearlyPrice: number
  trialDays: number
  currency: string
  isActive: boolean
  updatedAt: string
}

/** モジュール価格更新リクエスト */
export interface UpdateModulePricingRequest {
  monthlyPrice: number
  yearlyPrice: number
  trialDays: number
}

/** 価格変更履歴レスポンス */
export interface ModulePricingHistoryResponse {
  id: number
  moduleId: number
  field: string
  oldValue: string
  newValue: string
  changedBy: number
  changedByName: string
  changedAt: string
}
