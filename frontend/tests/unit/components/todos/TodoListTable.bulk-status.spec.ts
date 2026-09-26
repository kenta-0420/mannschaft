// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import TodoListTable from '~/components/todos/TodoListTable.vue'

const { bulkChangeTodoStatus, listTodos, notification } = vi.hoisted(() => ({
  bulkChangeTodoStatus: vi.fn(),
  listTodos: vi.fn(),
  notification: { success: vi.fn(), warn: vi.fn(), error: vi.fn() },
}))

vi.mock('~/composables/useTodoApi', () => ({
  useTodoApi: () => ({ bulkChangeTodoStatus, listTodos }),
}))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notification }))
vi.mock('~/composables/useUndoToast', () => ({ useUndoToast: () => ({ showUndoToast: vi.fn() }) }))
vi.mock('~/composables/useDatetime', () => ({ useDatetime: () => ({ userTimezone: ref('UTC') }) }))

const todoRows = [
  { id: 1, content: { title: 'TODO 1' }, assignees: [] },
  { id: 2, content: { title: 'TODO 2' }, assignees: [] },
]

const i18n = createI18n({
  legacy: false,
  locale: 'ja',
  messages: {
    ja: {
      todo: {
        list: {
          bulkStatusSuccess: '{count}件のステータスを変更しました',
          bulkStatusPartial: '{changedCount}件を変更し、ロック中の{skippedCount}件はスキップしました。スキップした項目は選択状態です',
          bulkStatusAllLocked: 'すべてロック中のため変更できませんでした（{count}件）。項目は選択状態です',
        },
      },
    },
    en: {},
    zh: {},
    ko: {},
    es: {},
    de: {},
  },
})

const DataTableStub = {
  name: 'DataTable',
  props: ['selection'],
  emits: ['update:selection'],
  template: '<div><slot /></div>',
}

const ButtonStub = {
  props: ['label'],
  emits: ['click'],
  template: '<button @click="$emit(\'click\')">{{ label }}</button>',
}

describe('TodoListTable bulk status change', () => {
  beforeEach(() => {
    bulkChangeTodoStatus.mockReset()
    listTodos.mockReset().mockResolvedValue({ data: todoRows, meta: { total: 2 } })
    notification.success.mockReset()
    notification.warn.mockReset()
    notification.error.mockReset()
  })

  it('成功した件数を通知し、再読込後に選択を解除する', async () => {
    bulkChangeTodoStatus.mockResolvedValue({ data: [{ id: 1 }, { id: 2 }], skippedLockedIds: [] })
    const wrapper = await mountTable()
    await selectRows(wrapper)

    await wrapper.get('button').trigger('click')
    await flushMicrotasks()

    expect(notification.success).toHaveBeenCalledWith('2件のステータスを変更しました')
    expect(listTodos).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(DataTableStub).props('selection')).toEqual([])
  })

  it('部分ロック時は変更数とスキップ数を通知し、スキップ行の選択を残す', async () => {
    bulkChangeTodoStatus.mockResolvedValue({ data: [{ id: 1 }], skippedLockedIds: [2] })
    const wrapper = await mountTable()
    await selectRows(wrapper)

    await wrapper.get('button').trigger('click')
    await flushMicrotasks()

    expect(notification.warn).toHaveBeenCalledWith(
      '1件を変更し、ロック中の1件はスキップしました。スキップした項目は選択状態です',
    )
    expect(listTodos).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(DataTableStub).props('selection')).toEqual([todoRows[1]])
  })

  it('全件ロック時は成功通知を出さず、全行の選択を残す', async () => {
    bulkChangeTodoStatus.mockResolvedValue({ data: [], skippedLockedIds: [1, 2] })
    const wrapper = await mountTable()
    await selectRows(wrapper)

    await wrapper.get('button').trigger('click')
    await flushMicrotasks()

    expect(notification.success).not.toHaveBeenCalled()
    expect(notification.warn).toHaveBeenCalledWith(
      'すべてロック中のため変更できませんでした（2件）。項目は選択状態です',
    )
    expect(listTodos).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent(DataTableStub).props('selection')).toEqual(todoRows)
  })
})

async function mountTable() {
  return mount(TodoListTable, {
    props: { scopeType: 'team', scopeId: 'team-1', canEdit: true, canDelete: false },
    global: {
      plugins: [i18n],
      stubs: {
        DataTable: DataTableStub,
        Column: true,
        Button: ButtonStub,
        Select: true,
        SelectButton: true,
        TodoStatusLabelBadge: true,
        TodoPriorityBadge: true,
        DashboardEmptyState: true,
      },
    },
  })
}

async function selectRows(wrapper: Awaited<ReturnType<typeof mountTable>>) {
  const table = wrapper.findComponent(DataTableStub)
  table.vm.$emit('update:selection', todoRows)
  await flushMicrotasks()
}

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}
