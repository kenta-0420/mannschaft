<script setup lang="ts">
import type { MyTodo, KanbanCol } from '~/composables/useTodoList'
import { priorityBorder, priorityLabel, priorityClass } from '~/composables/useTodoList'
import { SYSTEM_LABEL_ID, type TodoStatusLabelBucket } from '~/types/todoStatusLabel'

/**
 * F02.3.1: 旧前後遷移ボタン（左右矢印）を完全撤去。
 * バケット間移動は HTML5 ドラッグ&ドロップで行い、
 * 移動先バケットの SYSTEM 既定ラベル ID を statusLabelId として PATCH する（§5.7）。
 */
const props = defineProps<{
  kanbanCols: KanbanCol[]
  scopeDisplayName: (todo: MyTodo) => string
  scopeColor: (scopeType: string) => string
  formatDate: (d: string | null) => string
  isOverdue: (todo: MyTodo) => boolean
}>()

const emit = defineEmits<{
  changeStatus: [todo: MyTodo, payload: { statusLabelId: number }]
  create: []
}>()

const draggingTodo = ref<MyTodo | null>(null)

function todoLink(todo: MyTodo): string {
  if (todo.scopeType === 'TEAM' && todo.scopeSlug) {
    return `/teams/${todo.scopeSlug}/todos/${todo.id}`
  }
  if (todo.scopeType === 'ORGANIZATION' && todo.scopeSlug) {
    return `/organizations/${todo.scopeSlug}/todos/${todo.id}`
  }
  // PERSONAL または scopeSlug が未設定（slug 移行前の古いデータの保険）
  return `/todos/${todo.id}`
}

function bucketDefaultLabelId(bucket: string): number | null {
  if (bucket === 'OPEN') return SYSTEM_LABEL_ID.OPEN
  if (bucket === 'IN_PROGRESS') return SYSTEM_LABEL_ID.IN_PROGRESS
  if (bucket === 'COMPLETED') return SYSTEM_LABEL_ID.COMPLETED
  return null
}

function onDragStart(todo: MyTodo, ev: DragEvent) {
  draggingTodo.value = todo
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move'
    // テキストデータを設定しないと一部ブラウザで drop イベントが発火しない
    ev.dataTransfer.setData('text/plain', String(todo.id))
  }
}

function onDragOver(ev: DragEvent) {
  ev.preventDefault()
  if (ev.dataTransfer) {
    ev.dataTransfer.dropEffect = 'move'
  }
}

function onDrop(targetBucket: TodoStatusLabelBucket, ev: DragEvent) {
  ev.preventDefault()
  const todo = draggingTodo.value
  draggingTodo.value = null
  if (!todo) return
  if (todo.status === targetBucket) return
  const labelId = bucketDefaultLabelId(targetBucket)
  if (labelId === null) return
  emit('changeStatus', todo, { statusLabelId: labelId })
}

// 各列の status を bucket と見なす（型安全化）
function isBucket(v: string): v is TodoStatusLabelBucket {
  return v === 'OPEN' || v === 'IN_PROGRESS' || v === 'COMPLETED'
}

interface SafeKanbanCol extends Omit<KanbanCol, 'status'> {
  status: TodoStatusLabelBucket
}

const safeKanbanCols = computed<SafeKanbanCol[]>(() =>
  props.kanbanCols
    .filter((c): c is SafeKanbanCol => isBucket(c.status))
    .map((c) => c),
)
</script>

<template>
  <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
    <div
      v-for="col in safeKanbanCols"
      :key="col.status"
      class="rounded-xl border-2 border-surface-400 dark:border-surface-500"
      @dragover="onDragOver"
      @drop="onDrop(col.status, $event)"
    >
      <div class="flex items-center justify-between rounded-t-xl px-4 py-3" :class="col.color">
        <span class="font-semibold" :class="col.headerColor">{{ col.label }}</span>
        <span
          class="rounded-full bg-white/60 px-2 py-0.5 text-xs font-bold dark:bg-black/20"
          :class="col.headerColor"
        >
          {{ col.todos.length }}
        </span>
      </div>

      <div class="space-y-2 p-3">
        <div
          v-for="todo in col.todos"
          :key="todo.id"
          class="rounded-lg border-2 border-surface-400 bg-surface-0 p-3 shadow-sm transition-opacity dark:border-surface-500 dark:bg-surface-800"
          :class="[
            priorityBorder[todo.priority],
            { 'opacity-50': draggingTodo?.id === todo.id },
          ]"
          draggable="true"
          @dragstart="onDragStart(todo, $event)"
        >
          <NuxtLink
            :to="todoLink(todo)"
            class="mb-2 block text-sm font-medium leading-snug hover:text-primary"
            :class="
              todo.status === 'COMPLETED'
                ? 'text-surface-400 line-through'
                : 'text-surface-800 dark:text-surface-100'
            "
          >
            {{ todo.content?.title }}
          </NuxtLink>

          <div class="flex flex-wrap items-center gap-1.5">
            <span
              class="rounded-full px-1.5 py-0.5 text-[10px] font-medium"
              :class="scopeColor(todo.scopeType)"
            >
              {{ scopeDisplayName(todo) }}
            </span>
            <span
              class="rounded-full px-1.5 py-0.5 text-[10px] font-semibold"
              :class="priorityClass[todo.priority]"
            >
              {{ priorityLabel[todo.priority] }}
            </span>
            <TodoStatusLabelBadge
              :label="todo.statusLabel"
              :fallback-bucket="todo.status"
            />
            <span
              v-if="todo.dueDate"
              class="flex items-center gap-0.5 text-[10px]"
              :class="isOverdue(todo) ? 'text-red-500 font-semibold' : 'text-surface-400'"
            >
              <i class="pi pi-calendar" />{{ formatDate(todo.dueDate) }}
            </span>
          </div>

          <div v-if="todo.assignees.length > 0" class="mt-2 flex -space-x-1">
            <Avatar
              v-for="a in todo.assignees.slice(0, 4)"
              :key="a.userId"
              v-tooltip="a.displayName"
              :image="a.avatarUrl ?? undefined"
              :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
              size="small"
              shape="circle"
              class="border-2 border-surface-0 dark:border-surface-800"
            />
          </div>
        </div>

        <div v-if="col.todos.length === 0" class="py-6 text-center text-xs text-surface-400">
          なし
        </div>

        <button
          v-if="col.status === 'OPEN'"
          class="flex w-full items-center gap-2 rounded-lg border border-dashed border-surface-300 px-3 py-2 text-sm text-surface-400 transition-colors hover:border-primary hover:text-primary dark:border-surface-600"
          @click="emit('create')"
        >
          <i class="pi pi-plus" />追加する
        </button>
      </div>
    </div>
  </div>
</template>
