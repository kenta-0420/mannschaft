import type { TodoResponse } from '~/types/todo'
import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'

/**
 * BE の TodoResponse は status を `{ status, priority, completedAt, statusLabel }` の入れ子で返す。
 * 一方 FE の TodoResponse 型は旧フラット形（status が文字列）のまま他画面でも使われているため、
 * 型全体は変えず、プロジェクト関連タスク行の入口でこの関数により実態の入れ子形を正規化する。
 */
export interface TodoStatusNested {
  status?: string | null
  priority?: string | null
  completedAt?: string | null
  statusLabel?: TodoStatusLabelInfo | null
}

export interface ProjectRelatedTodoView {
  id: number
  title: string
  dueDate: string | null
  statusBucket: string | null
  statusLabel: TodoStatusLabelInfo | null
  isCompleted: boolean
  assigneeNames: string
}

export function normalizeProjectRelatedTodo(todo: TodoResponse): ProjectRelatedTodoView {
  // 実態は入れ子オブジェクト。旧フラット形（文字列）も後方互換で受ける。
  const raw = todo.status as unknown as string | TodoStatusNested | null | undefined
  const nested: TodoStatusNested = raw !== null && typeof raw === 'object' ? raw : { status: raw ?? null }
  const bucket = nested.status ?? null
  const label = nested.statusLabel ?? todo.statusLabel ?? null
  return {
    id: todo.id,
    title: todo.content?.title ?? '',
    dueDate: todo.schedule?.dueDate ?? null,
    statusBucket: bucket,
    statusLabel: label,
    isCompleted: bucket === 'COMPLETED' || label?.bucket === 'COMPLETED',
    assigneeNames: (todo.assignees ?? []).map((a) => a.displayName).join(', '),
  }
}
