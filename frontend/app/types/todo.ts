import type { TodoStatusLabelInfo } from './todoStatusLabel'

export type TodoStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED'
export type TodoPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type TodoScopeType = 'PERSONAL' | 'TEAM' | 'ORGANIZATION'

/** Wave 1 DTO刷新: TodoResponse ネスト構造 */

export interface TodoScopeDto {
  scopeType?: string
  scopeId?: number
  projectId?: number | null
  milestoneId?: number | null
  /** TEAM / ORGANIZATION の slug（URLルーティング用）。PERSONAL は null。 */
  scopeSlug?: string | null
}

export interface TodoContentDto {
  title?: string
  description?: string | null
  startDate?: string | null
  progressRate?: number
  progressManual?: boolean
  sortOrder?: number
}

export interface TodoScheduleDto {
  dueDate?: string | null
  dueTime?: string | null
  daysRemaining?: number | null
  linkedScheduleId?: number | null
}

export interface TodoStatusDto {
  status?: TodoStatus
  priority?: TodoPriority
  completedAt?: string | null
  completedBy?: { id: number; displayName: string } | null
  statusLabel?: TodoStatusLabelInfo | null
}

export interface TodoHierarchyDto {
  parentId?: number | null
  depth?: number
  children?: TodoResponse[]
  childCount?: number
  descendantCompletedCount?: number
  descendantTotalCount?: number
}

export interface TodoAuditDto {
  createdAt?: string
  updatedAt?: string
  createdBy?: { id: number; displayName: string }
  completedBy?: { id: number; displayName: string } | null
}

export interface TodoResponse {
  id: number
  scope?: TodoScopeDto
  content?: TodoContentDto
  schedule?: TodoScheduleDto
  /** ステータスバケット — @deprecated 旧フラットフィールド互換 */
  status?: TodoStatus
  /** @deprecated 旧フラットフィールド互換 */
  priority?: TodoPriority
  /** F02.3.1 — カスタムステータスラベル情報 — @deprecated 旧フラットフィールド互換 */
  statusLabel?: TodoStatusLabelInfo | null
  hierarchy?: TodoHierarchyDto
  audit?: TodoAuditDto
  assignees: TodoAssigneeResponse[]
  // F02.7 マイルストーンゲート関連
  milestoneLocked: boolean
  position: number
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

/** 自分に割り当てられたTODOをカレンダーへ載せるための軽量DTO。 */
export interface MyCalendarTodo {
  id: number
  title: string
  startDate: string | null
  dueDate: string
  dueTime: string | null
  status: TodoStatus
  priority: TodoPriority
  linkedScheduleId: number | null
  scopeType: TodoScopeType
  scopeId: number | null
  scopeSlug: string | null
  scopeName: string | null
}

export interface MyCalendarTodoResponse {
  data: MyCalendarTodo[]
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
