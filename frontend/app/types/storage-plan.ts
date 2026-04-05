export interface StoragePlanResponse {
  id: number
  name: string
  freeQuotaBytes: number
  monthlyPrice: number
  yearlyPrice: number
  overageUnitPrice: number
  hardCapBytes: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateStoragePlanRequest {
  name: string
  freeQuotaBytes: number
  monthlyPrice: number
  yearlyPrice: number
  overageUnitPrice: number
  hardCapBytes: number
}

export interface UpdateStoragePlanRequest {
  name?: string
  freeQuotaBytes?: number
  monthlyPrice?: number
  yearlyPrice?: number
  overageUnitPrice?: number
  hardCapBytes?: number
  isActive?: boolean
}

export interface TeamStorageUsageResponse {
  teamId: number
  teamName: string
  planId: number
  planName: string
  usedBytes: number
  totalBytes: number
  usagePercent: number
}
