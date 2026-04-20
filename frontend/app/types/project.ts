export interface ProjectResponse {
  id: number
  title: string
  emoji: string | null
  color: string | null
  dueDate: string | null
  daysRemaining: number | null
  status: string
  progressRate: number
  totalTodos: number
  completedTodos: number
  milestones: { total: number; completed: number }
  createdBy: { id: number; displayName: string }
  createdAt: string
}

export interface CreateProjectRequest {
  title?: string
  description?: string
  emoji?: string
  color?: string
  dueDate?: string
  visibility?: string
}

export interface UpdateProjectRequest {
  title?: string
  description?: string
  emoji?: string
  color?: string
  dueDate?: string
  visibility?: string
  status?: string
}

export type MilestoneCompletionMode = 'AUTO' | 'MANUAL'

export interface MilestoneResponse {
  id: number
  projectId: number
  title: string
  dueDate: string | null
  sortOrder: number
  completed: boolean
  completedAt: string | null
  createdAt: string
  updatedAt: string
  // F02.7 ゲート関連フィールド
  progressRate: number
  isLocked: boolean
  lockedByMilestoneId: number | null
  lockedByMilestoneTitle: string | null
  completionMode: MilestoneCompletionMode
  lockedTodoCount: number
  forceUnlocked: boolean
  lockedAt: string | null
  unlockedAt: string | null
}

export interface CreateMilestoneRequest {
  title?: string
  dueDate?: string
  sortOrder?: number
}

export interface UpdateMilestoneRequest {
  title?: string
  dueDate?: string
  sortOrder?: number
}

// === F02.7 マイルストーンゲート関連 ===

export interface NextGate {
  id: number
  title: string
  lockedReasonMilestoneId: number | null
  lockedReasonMilestoneTitle: string | null
  previousProgressRate: number
}

export interface MilestoneGateInfo {
  id: number
  title: string
  sortOrder: number
  isCompleted: boolean
  isLocked: boolean
  lockedByMilestoneId: number | null
  lockedByMilestoneTitle: string | null
  progressRate: number
  completionMode: MilestoneCompletionMode
  totalTodos: number
  completedTodos: number
  lockedTodoCount: number
  lockedAt: string | null
  completedAt: string | null
}

export interface GatesSummaryResponse {
  projectId: number
  overallProgressRate: number
  gateCompletionRate: number
  totalMilestones: number
  completedMilestones: number
  lockedMilestones: number
  nextGate: NextGate | null
  milestones: MilestoneGateInfo[]
}

export interface ChangeCompletionModeRequest {
  completionMode: MilestoneCompletionMode
}

export interface ForceUnlockRequest {
  reason: string
}

export interface ForceUnlockResponse {
  milestoneId: number
  unlockedAt: string
  forcedByUserId: number
  reason: string
}

export interface InitializeGateResponse {
  initializedMilestoneCount: number
  lockedMilestoneCount: number
  unlockedMilestoneCount: number
  lockedTodoCount: number
  unlockedTodoCount: number
  updatedAt: string
}

export interface ReorderTodosRequest {
  todoIds: number[]
}
