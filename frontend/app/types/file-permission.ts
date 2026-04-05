export interface CreatePermissionRequest {
  targetType: string
  targetId: number
  permissionType: string
  permissionTargetType: string
  permissionTargetId: number
}

export interface FilePermissionResponse {
  id: number
  targetType: string
  targetId: number
  permissionType: string
  permissionTargetType: string
  permissionTargetId: number
  createdAt: string
}
