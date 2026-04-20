<script setup lang="ts">
import type { MilestoneCompletionMode, MilestoneResponse } from '~/types/project'
import type { TodoResponse } from '~/types/todo'

const props = withDefaults(
  defineProps<{
    milestones: MilestoneResponse[]
    canEdit: boolean
    canForceUnlock?: boolean
    todos?: TodoResponse[]
    teamId?: number | null
    projectId?: number
  }>(),
  {
    canForceUnlock: false,
    todos: () => [],
    teamId: null,
    projectId: undefined,
  },
)

const emit = defineEmits<{
  create: []
  edit: [ms: MilestoneResponse]
  toggleComplete: [ms: MilestoneResponse]
  remove: [ms: MilestoneResponse]
  'force-unlock': [milestone: MilestoneResponse]
  'change-completion-mode': [milestoneId: number, mode: MilestoneCompletionMode]
  /** TODO 並び替え成功後に親へ通知（必要なら親でリロードする） */
  'todos-reordered': [milestoneId: number, todoIds: number[]]
}>()

const projectApi = useProjectApi()
const notification = useNotification()
const { success: showSuccess, error: showError, warn: showWarn } = notification
const { t } = useI18n()

// マイルストーンID毎のローカル TODO 配列（Optimistic UI 管理）
// props.todos は親から渡される全体。ここではマイルストーン単位でグルーピングしてローカル保持する。
const localTodosByMilestone = ref<Record<number, TodoResponse[]>>({})

function sortTodos(list: TodoResponse[]): TodoResponse[] {
  return [...list].sort((a, b) => {
    if (a.position !== b.position) return a.position - b.position
    return a.id - b.id
  })
}

function rebuildLocalTodos() {
  const next: Record<number, TodoResponse[]> = {}
  for (const ms of props.milestones) {
    next[ms.id] = sortTodos(props.todos.filter((todo) => todo.milestoneId === ms.id))
  }
  localTodosByMilestone.value = next
}

watch(
  () => [props.milestones, props.todos] as const,
  () => {
    rebuildLocalTodos()
  },
  { immediate: true, deep: true },
)

function todosFor(milestoneId: number): TodoResponse[] {
  return localTodosByMilestone.value[milestoneId] ?? []
}

// === Drag & Drop 状態 ===
// milestoneId 単位で drag 中 index / drop target index を管理する
const dragMilestoneId = ref<number | null>(null)
const dragIndex = ref<number | null>(null)
const dropTargetIndex = ref<number | null>(null)

function isDraggable(ms: MilestoneResponse): boolean {
  // MEMBER は不可、ロック中マイルストーン配下も不可
  if (!props.canEdit) return false
  if (ms.isLocked) return false
  return true
}

function onDragStart(ms: MilestoneResponse, index: number, e: DragEvent) {
  if (!isDraggable(ms)) {
    e.preventDefault()
    return
  }
  dragMilestoneId.value = ms.id
  dragIndex.value = index
  dropTargetIndex.value = null
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    // Firefox は dataTransfer に何か設定しないと drag が発火しない
    try {
      e.dataTransfer.setData('text/plain', String(index))
    } catch {
      // noop
    }
  }
}

function onDragOver(ms: MilestoneResponse, index: number, e: DragEvent) {
  if (!isDraggable(ms)) return
  // 同一マイルストーン内の drag のみ許可
  if (dragMilestoneId.value !== ms.id) return
  e.preventDefault()
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = 'move'
  }
  dropTargetIndex.value = index
}

function onDragLeave() {
  dropTargetIndex.value = null
}

function onDragEnd() {
  dragMilestoneId.value = null
  dragIndex.value = null
  dropTargetIndex.value = null
}

async function onDrop(ms: MilestoneResponse, toIndex: number) {
  // ガード条件: 同一マイルストーンでない場合は無効化
  if (dragMilestoneId.value !== ms.id) {
    onDragEnd()
    return
  }
  const fromIndex = dragIndex.value
  if (fromIndex === null || fromIndex === toIndex) {
    onDragEnd()
    return
  }
  if (!isDraggable(ms)) {
    onDragEnd()
    return
  }

  const currentList = todosFor(ms.id)
  if (fromIndex < 0 || fromIndex >= currentList.length) {
    onDragEnd()
    return
  }
  if (toIndex < 0 || toIndex >= currentList.length) {
    onDragEnd()
    return
  }

  // Optimistic UI: ローカル配列を即時入れ替え
  const originalOrder = [...currentList]
  const reordered = [...currentList]
  const [moved] = reordered.splice(fromIndex, 1)
  if (moved) {
    reordered.splice(toIndex, 0, moved)
  }
  localTodosByMilestone.value = {
    ...localTodosByMilestone.value,
    [ms.id]: reordered,
  }

  const todoIds = reordered.map((todo) => todo.id)
  onDragEnd()

  // API 呼び出し（projectId が不明なら呼べない）
  if (props.projectId === undefined) {
    showWarn(t('project.reorder_failed'))
    // 復元
    localTodosByMilestone.value = {
      ...localTodosByMilestone.value,
      [ms.id]: originalOrder,
    }
    return
  }

  try {
    await projectApi.reorderMilestoneTodos(props.teamId, props.projectId, ms.id, todoIds)
    showSuccess(t('project.reorder_success'))
    emit('todos-reordered', ms.id, todoIds)
  } catch (err) {
    // 423 Locked / その他エラーともに元の順序へ復帰
    localTodosByMilestone.value = {
      ...localTodosByMilestone.value,
      [ms.id]: originalOrder,
    }
    const apiErr =
      typeof err === 'object' && err !== null && 'data' in err
        ? (err as { data?: { errorCode?: string; message?: string } }).data
        : null
    if (apiErr?.errorCode === 'MILESTONE_LOCKED') {
      showWarn(t('project.reorder_failed'))
    } else {
      showError(apiErr?.message ?? t('project.reorder_failed'))
    }
  }
}

function todoStatusSeverity(status: TodoResponse['status']): 'secondary' | 'info' | 'success' {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'IN_PROGRESS':
      return 'info'
    default:
      return 'secondary'
  }
}
</script>

<template>
  <div class="mb-6">
    <div class="mb-2 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ $t('project.milestones') }}</h2>
      <Button
        v-if="canEdit"
        icon="pi pi-plus"
        :label="$t('button.create')"
        text
        size="small"
        @click="emit('create')"
      />
    </div>
    <div class="flex flex-col gap-2">
      <div
        v-for="ms in milestones"
        :key="ms.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
        :class="ms.isLocked ? 'opacity-60' : ''"
      >
        <div class="flex items-center justify-between">
          <div class="flex flex-1 items-center gap-3">
            <Checkbox
              :model-value="ms.completed"
              :binary="true"
              :disabled="ms.isLocked"
              @update:model-value="emit('toggleComplete', ms)"
            />
            <div class="flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <p :class="ms.completed ? 'text-surface-400 line-through' : 'font-medium'">
                  {{ ms.title }}
                </p>
                <LockedTodoBadge
                  :locked="ms.isLocked"
                  :locked-by-title="ms.lockedByMilestoneTitle"
                />
                <Tag
                  v-if="ms.completionMode === 'MANUAL'"
                  severity="info"
                  icon="pi pi-user-edit"
                  :value="$t('project.completion_mode_manual')"
                />
                <Tag
                  v-else
                  severity="secondary"
                  icon="pi pi-cog"
                  :value="$t('project.completion_mode_auto')"
                />
                <Tag
                  v-if="ms.forceUnlocked"
                  severity="danger"
                  icon="pi pi-unlock"
                  :value="$t('project.force_unlocked')"
                />
              </div>
              <p v-if="ms.dueDate" class="mt-1 text-xs text-surface-500">
                {{ $t('project.due_date') }}: {{ ms.dueDate }}
              </p>
              <p
                v-if="ms.lockedTodoCount > 0"
                class="mt-1 text-xs text-amber-600 dark:text-amber-400"
              >
                <i class="pi pi-lock mr-1" />
                {{ $t('project.locked_todo_count', { count: ms.lockedTodoCount }) }}
              </p>
              <div v-if="canEdit" class="mt-2">
                <CompletionModeToggle
                  :mode="ms.completionMode"
                  :disabled="!canEdit"
                  @update:mode="emit('change-completion-mode', ms.id, $event)"
                />
              </div>
            </div>
          </div>
          <div v-if="canEdit" class="flex gap-1">
            <Button
              v-if="ms.isLocked && canForceUnlock"
              icon="pi pi-unlock"
              text
              rounded
              size="small"
              severity="warning"
              :aria-label="$t('project.force_unlock_title')"
              :data-testid="`force-unlock-trigger-${ms.id}`"
              @click="emit('force-unlock', ms)"
            />
            <Button
              icon="pi pi-pencil"
              text
              rounded
              size="small"
              @click="emit('edit', ms)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="emit('remove', ms)"
            />
          </div>
        </div>
        <div class="mt-2 flex items-center gap-2">
          <ProgressBar
            :value="Math.round(ms.progressRate)"
            :show-value="false"
            class="flex-1"
            style="height: 6px"
          />
          <span class="w-12 text-right text-xs text-surface-500">
            {{ ms.progressRate.toFixed(1) }}%
          </span>
        </div>

        <!-- === 配下 TODO リスト（F02.7 drag-and-drop 並び替え対応） === -->
        <div
          v-if="todosFor(ms.id).length > 0"
          class="mt-3 flex flex-col gap-1"
          :data-testid="`milestone-todo-list-${ms.id}`"
        >
          <div
            v-for="(todo, idx) in todosFor(ms.id)"
            :key="todo.id"
            :data-testid="`draggable-todo-item-${todo.id}`"
            :draggable="isDraggable(ms) && !todo.milestoneLocked ? 'true' : 'false'"
            class="flex items-center gap-2 rounded-md border border-surface-100 bg-surface-50 p-2 transition-colors dark:border-surface-600 dark:bg-surface-700"
            :class="[
              dragMilestoneId === ms.id && dragIndex === idx
                ? 'opacity-50'
                : dragMilestoneId === ms.id && dropTargetIndex === idx
                  ? 'border-primary bg-primary/10'
                  : '',
              todo.milestoneLocked ? 'opacity-60' : '',
            ]"
            @dragstart="onDragStart(ms, idx, $event)"
            @dragover="onDragOver(ms, idx, $event)"
            @dragleave="onDragLeave"
            @drop="onDrop(ms, idx)"
            @dragend="onDragEnd"
          >
            <span
              v-if="isDraggable(ms) && !todo.milestoneLocked"
              :data-testid="`todo-drag-handle-${todo.id}`"
              class="cursor-grab text-surface-400 active:cursor-grabbing"
              :title="$t('project.drag_to_reorder')"
              :aria-label="$t('project.drag_to_reorder')"
            >
              <i class="pi pi-bars text-sm" />
            </span>
            <span
              v-else-if="todo.milestoneLocked"
              class="text-surface-300"
              :data-testid="`todo-drag-handle-disabled-${todo.id}`"
            >
              <i class="pi pi-lock text-sm" />
            </span>
            <Tag
              :severity="todoStatusSeverity(todo.status)"
              :value="todo.status"
              class="text-xs"
            />
            <span
              class="flex-1 truncate text-sm"
              :class="todo.status === 'COMPLETED' ? 'text-surface-400 line-through' : ''"
            >
              {{ todo.title }}
            </span>
            <span v-if="todo.dueDate" class="text-xs text-surface-500">
              {{ todo.dueDate }}
            </span>
          </div>
        </div>
      </div>
      <div v-if="milestones.length === 0" class="py-4 text-center text-surface-400">
        {{ $t('project.no_milestones') }}
      </div>
    </div>
  </div>
</template>
