
import { describe, expect, it } from 'vitest'

import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ProjectMilestoneList from './ProjectMilestoneList.vue'
import type { MilestoneResponse } from '~/types/project'

mockNuxtImport('useProjectApi', () => () => ({}))
mockNuxtImport('useNotification', () => () => ({ success: () => {}, error: () => {}, warn: () => {} }))

/**
 * CMP-261105-1507: BE の JSON キーは `locked`（`isLocked` ではない）。
 * 旧実装は ms.isLocked（常に undefined）を LockedTodoBadge の Boolean prop に渡して Vue 警告を出し、
 * ロック表示・無効化も効いていなかった。
 */
function milestone(overrides: Partial<MilestoneResponse>): MilestoneResponse {
  return {
    id: 1,
    projectId: 1,
    title: 'M1',
    dueDate: null,
    sortOrder: 0,
    completed: false,
    completedAt: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    progressRate: 0,
    locked: false,
    lockedByMilestoneId: null,
    lockedByMilestoneTitle: null,
    completionMode: 'AUTO',
    lockedTodoCount: 0,
    forceUnlocked: false,
    lockedAt: null,
    unlockedAt: null,
    ...overrides,
  }
}

const stubs = {
  Checkbox: { props: ['modelValue', 'disabled'], template: '<input type="checkbox" :disabled="disabled" />' },
  Tag: { props: ['value'], template: '<span class="tag">{{ value }}</span>' },
  Button: true,
  ProgressBar: true,
  CompletionModeToggle: true,
  TodoStatusLabelBadge: true,
}

describe('ProjectMilestoneList', () => {
  it('locked: true のマイルストーンはロック表示になり、チェックが無効になる', async () => {
    const w = await mountSuspended(ProjectMilestoneList, {
      props: { milestones: [milestone({ locked: true, lockedByMilestoneTitle: '前段' })], canEdit: true },
      global: { stubs, directives: { tooltip: {} } },
    })
    expect(w.text()).toContain('Locked')
    expect(w.find('input[type="checkbox"]').attributes('disabled')).toBeDefined()
  })

  it('locked: false のマイルストーンはロック表示にならない', async () => {
    const w = await mountSuspended(ProjectMilestoneList, {
      props: { milestones: [milestone({ locked: false })], canEdit: true },
      global: { stubs, directives: { tooltip: {} } },
    })
    expect(w.text()).not.toContain('Locked')
    expect(w.find('input[type="checkbox"]').attributes('disabled')).toBeUndefined()
  })
})
