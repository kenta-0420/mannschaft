// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { countMilestones, normalizeProjectDetail, toProgressPercent } from './projectDetail'
import type { ProjectDetailResponse } from '~/types/project'

/**
 * CMP-261105-1507: プロジェクト詳細・一覧の表示値の回帰テスト。
 * BE の progressRate は 0〜100 の百分率（ProjectRepository.recalculateProgress の `* 100.0`）。
 */
describe('toProgressPercent', () => {
  it('百分率をそのまま四捨五入する（33.33 は 33、100 倍しない）', () => {
    expect(toProgressPercent(33.33)).toBe(33)
    expect(toProgressPercent(50)).toBe(50)
    expect(toProgressPercent(100)).toBe(100)
  })

  it('未設定・不正値は 0、範囲外は 0〜100 に丸める', () => {
    expect(toProgressPercent(null)).toBe(0)
    expect(toProgressPercent(undefined)).toBe(0)
    expect(toProgressPercent(Number.NaN)).toBe(0)
    expect(toProgressPercent(250)).toBe(100)
    expect(toProgressPercent(-5)).toBe(0)
  })
})

describe('countMilestones', () => {
  it('詳細 API の配列（各要素に completed）から完了数と総数を数える', () => {
    expect(countMilestones([{ completed: true }, { completed: false }, { completed: true }])).toEqual({
      total: 3,
      completed: 2,
    })
  })

  it('一覧 API の集計形 {total, completed} もそのまま読める', () => {
    expect(countMilestones({ total: 4, completed: 1 })).toEqual({ total: 4, completed: 1 })
  })

  it('空・未設定は 0/0', () => {
    expect(countMilestones([])).toEqual({ total: 0, completed: 0 })
    expect(countMilestones(undefined)).toEqual({ total: 0, completed: 0 })
  })
})

describe('normalizeProjectDetail', () => {
  const detail: ProjectDetailResponse = {
    id: 7,
    meta: { status: 'ACTIVE', visibility: 'TEAM' },
    content: { title: '春の大会', description: null, emoji: '🏆', color: '#3B82F6' },
    schedule: { dueDate: '2026-12-01', daysRemaining: 26 },
    progress: { progressRate: 33.33, totalTodos: 3, completedTodos: 1 },
    milestones: [
      { id: 1, title: 'a', dueDate: null, completed: true, completedAt: null, progressRate: 100, totalTodos: 1, completedTodos: 1, sortOrder: 0 },
      { id: 2, title: 'b', dueDate: null, completed: false, completedAt: null, progressRate: 0, totalTodos: 2, completedTodos: 0, sortOrder: 1 },
    ],
    unassignedTodos: { total: 0, completed: 0 },
    audit: { createdBy: { id: 1, displayName: '管理者' } },
  }

  it('ネスト形の詳細を画面用のフラット形にし、進捗は百分率のまま、マイルストーンは件数にする', () => {
    const view = normalizeProjectDetail(detail)
    expect(view.title).toBe('春の大会')
    expect(view.status).toBe('ACTIVE')
    expect(view.progressRate).toBe(33.33)
    expect(view.totalTodos).toBe(3)
    expect(view.completedTodos).toBe(1)
    expect(view.milestones).toEqual({ total: 2, completed: 1 })
    expect(view.dueDate).toBe('2026-12-01')
  })
})
