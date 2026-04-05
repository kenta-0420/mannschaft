/** 税率設定レスポンス */
export interface TaxSettingResponse {
  id: number
  name: string
  rate: number
  isIncludedInPrice: boolean
  isActive: boolean
  createdAt: string
  updatedAt: string
}

/** 税率設定作成リクエスト */
export interface CreateTaxSettingRequest {
  name: string
  rate: number
  isIncludedInPrice: boolean
  isActive: boolean
}

/** 税率設定更新リクエスト */
export interface UpdateTaxSettingRequest {
  name: string
  rate: number
  isIncludedInPrice: boolean
  isActive: boolean
}
