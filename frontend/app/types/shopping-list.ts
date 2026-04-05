export interface ShoppingListResponse {
  id: number
  teamId: number
  name: string
  status: string
  createdBy: number
  createdAt: string
  updatedAt: string
  template: boolean
}

export interface ShoppingListRequest {
  name?: string
  isTemplate?: boolean
}

export interface ShoppingItemResponse {
  id: number
  listId: number
  name: string
  quantity: string | null
  note: string | null
  assignedTo: number | null
  checked: boolean
  checkedBy: number | null
  checkedAt: string | null
  sortOrder: number
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface ShoppingItemRequest {
  name?: string
  quantity?: string
  note?: string
  assignedTo?: number
  sortOrder?: number
}
