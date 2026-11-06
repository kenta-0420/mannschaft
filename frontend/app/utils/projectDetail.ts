import type { ProjectDetailResponse, ProjectResponse } from '~/types/project'

/**
 * プロジェクト画面の表示値ヘルパ（CMP-261105-1507）。
 */

/**
 * 進捗率（BE は 0〜100 の百分率で返す）を表示用の整数％にする。
 * 以前は FE が更に 100 倍して 5000% などと出していたため、百分率はそのまま丸めるだけにする。
 */
export function toProgressPercent(rate: number | null | undefined): number {
  const n = Number(rate)
  if (!Number.isFinite(n)) return 0
  return Math.min(100, Math.max(0, Math.round(n)))
}

/**
 * マイルストーンの完了数と総数。
 * - 詳細 API: 配列（各要素の `completed`）から数える
 * - 一覧 API: 集計形 `{ total, completed }` をそのまま読む
 */
export function countMilestones(
  milestones: { completed: boolean }[] | { total: number; completed: number } | null | undefined,
): { total: number; completed: number } {
  if (!milestones) return { total: 0, completed: 0 }
  if (Array.isArray(milestones)) {
    return { total: milestones.length, completed: milestones.filter((m) => m.completed).length }
  }
  return { total: milestones.total ?? 0, completed: milestones.completed ?? 0 }
}

/**
 * ネスト形のプロジェクト詳細レスポンスを、画面が読むフラット形（ProjectResponse）に直す。
 * progressRate は BE の百分率のまま保持する（表示時に toProgressPercent で丸める）。
 */
export function normalizeProjectDetail(detail: ProjectDetailResponse): ProjectResponse {
  return {
    id: detail.id,
    title: detail.content.title,
    emoji: detail.content.emoji,
    color: detail.content.color,
    dueDate: detail.schedule.dueDate,
    daysRemaining: detail.schedule.daysRemaining,
    status: detail.meta.status,
    progressRate: detail.progress.progressRate,
    totalTodos: detail.progress.totalTodos,
    completedTodos: detail.progress.completedTodos,
    milestones: countMilestones(detail.milestones),
    createdBy: detail.audit.createdBy,
    createdAt: '',
  }
}
