export interface ChatFolderResponse {
  id: number
  name: string
  icon: string | null
  color: string | null
  sortOrder: number
  items: ChatFolderItemResponse[]
}

export interface ChatFolderItemResponse {
  id: number
  itemType: string
  itemId: number
}

export interface CreateChatFolderRequest {
  name?: string
  icon?: string
  color?: string
}

export interface UpdateChatFolderRequest {
  name?: string
  icon?: string
  color?: string
  sortOrder?: number
}

export interface AssignFolderItemRequest {
  itemType: string
  itemId: number
}

export interface BulkAssignFolderItemsRequest {
  items: AssignFolderItemRequest[]
}
