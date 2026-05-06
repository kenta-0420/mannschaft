export interface ScopeFolder {
  id: number
  name: string
  color: string | null
  sortOrder: number
  itemScopeIds: number[]
}

export interface CreateFolderRequest {
  name: string
  color?: string
}

export interface UpdateFolderRequest {
  name: string
  color?: string | null
}

export interface ReorderFoldersRequest {
  orderedIds: number[]
}
