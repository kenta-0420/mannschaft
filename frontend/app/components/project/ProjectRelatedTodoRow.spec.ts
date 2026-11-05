// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ProjectRelatedTodoRow from './ProjectRelatedTodoRow.vue'
import type { TodoResponse } from '~/types/todo'

const todo: TodoResponse = {
  id: 42,
  content: { title: '会場を予約する' },
  schedule: { dueDate: '2026-12-01' },
  status: 'IN_PROGRESS',
  assignees: [
    { id: 1, userId: 10, displayName: '山田太郎', avatarUrl: null, assignedBy: 1, createdAt: '2026-11-01T00:00:00' },
  ],
  milestoneLocked: false,
  position: 0,
}

function mountRow(t: TodoResponse) {
  return mount(ProjectRelatedTodoRow, {
    props: { todo: t, todoHref: '/teams/t1/todos/42' },
    global: {
      mocks: { $t: (k: string) => k },
      stubs: {
        NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        TodoStatusLabelBadge: {
          props: ['fallbackBucket'],
          template: '<span data-testid="status">{{ fallbackBucket }}</span>',
        },
      },
    },
  })
}

describe('ProjectRelatedTodoRow', () => {
  it('タイトル・状態・期限・担当者を出し、JSON を出さない', () => {
    const text = mountRow(todo).text()
    expect(text).toContain('会場を予約する')
    expect(text).toContain('IN_PROGRESS')
    expect(text).toContain('2026-12-01')
    expect(text).toContain('山田太郎')
    expect(text).not.toContain('{')
    expect(text).not.toContain('"id"')
  })

  it('TODO 詳細へのリンクを持つ', () => {
    expect(mountRow(todo).find('a').attributes('href')).toBe('/teams/t1/todos/42')
  })

  it('狭い幅ではみ出さないよう折り返し・縮小の指定がある', () => {
    const html = mountRow(todo).html()
    expect(html).toContain('min-w-0')
    expect(html).toContain('break-words')
  })
})
