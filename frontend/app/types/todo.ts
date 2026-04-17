export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type TodoScopeType = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

export interface TodoResponse {
  id: number
  scopeType: TodoScopeType
  scopeId: number
  projectId: number | null
  milestoneId: number | null
  title: string
  description: string | null
  status: TodoStatus
  priority: TodoPriority
  dueDate: string | null
  dueTime: string | null
  daysRemaining: number | null
  completedAt: string | null
  completedBy: { id: number; displayName: string } | null
  createdBy: { id: number; displayName: string }
  sortOrder: number
  assignees: TodoAssigneeResponse[]
  createdAt: string
  updatedAt: string
  // Phase 4 拡張フィールド
  startDate: string | null
  linkedScheduleId: number | null
  progressRate: string
  progressManual: boolean
}

export interface TodoAssigneeResponse {
  id: number
  userId: number
  displayName: string
  avatarUrl: string | null
  assignedBy: number
  createdAt: string
}

export interface CreateTodoRequest {
  title: string
  description?: string
  projectId?: number
  milestoneId?: number
  priority?: TodoPriority
  dueDate?: string
  dueTime?: string
  sortOrder?: number
  assigneeIds?: number[]
}

export interface UpdateTodoRequest {
  title?: string
  description?: string
  projectId?: number | null
  milestoneId?: number | null
  priority?: TodoPriority
  dueDate?: string | null
  dueTime?: string | null
  sortOrder?: number
}

export interface TodoCommentResponse {
  id: number
  todoId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

export interface CreateTodoCommentRequest {
  body: string
}

export interface BulkStatusChangeRequest {
  todoIds: number[]
  status: TodoStatus
}

// ガントバー表示用
export interface GanttTodo {
  id: number
  title: string
  startDate: string
  dueDate: string
  progressRate: string
  progressManual: boolean
  status: TodoStatus
  priority: TodoPriority
  parentId: number | null
  depth: number
  childIds: number[]
}

// 共有メモエントリ
export interface SharedMemoEntry {
  id: number
  todoId: number
  userId: number
  userDisplayName: string
  memo: string
  quotedEntryId: number | null
  quotedMemoPreview: string | null
  createdAt: string
  updatedAt: string
  isEditable: boolean
  isOwnMemo: boolean
}

// 個人メモ
export interface PersonalMemo {
  userId: number
  todoId: number
  memo: string
  createdAt: string
  updatedAt: string
}

// ガント取得レスポンス
export interface GanttResponse {
  data: GanttTodo[]
  fromDate: string
  toDate: string
}

// 進捗率更新リクエスト
export interface UpdateProgressRequest {
  progressRate: string
}

// 進捗モード更新リクエスト
export interface UpdateProgressModeRequest {
  progressManual: boolean
}

// スケジュール連携リクエスト
export interface LinkScheduleRequest {
  scheduleId: number
}

// 共有メモ作成リクエスト
export interface CreateSharedMemoRequest {
  memo: string
  quotedEntryId?: number
}

// 共有メモ更新リクエスト
export interface UpdateSharedMemoRequest {
  memo: string
}

// 個人メモ UPSERT リクエスト
export interface UpsertPersonalMemoRequest {
  memo: string
}

// 共有メモ一覧レスポンス
export interface SharedMemoListResponse {
  data: SharedMemoEntry[]
  meta: { page: number; size: number; totalElements: number; totalPages: number }
}
