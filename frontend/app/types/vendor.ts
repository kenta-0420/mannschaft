/**
 * 物件履歴台帳（F09.13）— 業者マスタ 型定義
 *
 * バックエンド DTO:
 *  - VendorResponse, VendorRequest, VendorSuggestionResponse
 *  - 設計書: docs/features/F09.13_property_history.md §3
 */

export type VendorCategory =
  | 'CONSTRUCTION'
  | 'INSPECTION'
  | 'CONSULTING'
  | 'CLEANING'
  | 'SECURITY'
  | 'OTHER'

export interface VendorResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  name: string
  nameKana: string | null
  category: VendorCategory | null
  phone: string | null
  email: string | null
  website: string | null
  postalCode: string | null
  address: string | null
  representative: string | null
  contactPerson: string | null
  licenseNumber: string | null
  licenseExpiry: string | null
  note: string | null
  isActive: boolean
  version: number
  createdAt: string
  updatedAt: string
}

export interface VendorSuggestionResponse {
  id: number
  name: string
  nameKana: string | null
  category: VendorCategory | null
}

export interface VendorRequest {
  name: string
  nameKana?: string | null
  category?: VendorCategory | null
  phone?: string | null
  email?: string | null
  website?: string | null
  postalCode?: string | null
  address?: string | null
  representative?: string | null
  contactPerson?: string | null
  licenseNumber?: string | null
  licenseExpiry?: string | null
  note?: string | null
  isActive?: boolean
  /** 楽観的ロック用 version。PUT 時必須。 */
  version?: number | null
}
