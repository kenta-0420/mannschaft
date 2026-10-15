export interface PackageModuleRef {
  moduleId: number
  moduleName: string
  moduleSlug: string
}

export interface PackageResponse {
  id: number
  name: string
  description: string
  modules: PackageModuleRef[]
  price: number
  discountRate: number
  isPublished: boolean
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface CreatePackageRequest {
  name: string
  description?: string
  moduleIds: number[]
  price: number
  discountRate?: number
}

export interface UpdatePackageRequest {
  name?: string
  description?: string
  moduleIds?: number[]
  price?: number
  discountRate?: number
}
