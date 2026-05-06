<script setup lang="ts">
import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const todoId = Number(route.params.id)
const todoApi = useTodoApi()
const notification = useNotification()
const errorHandler = useErrorHandler()
const authStore = useAuthStore()

interface PersonalTodoDetail {
  id: number
  scopeType: string
  scopeId: number
  title: string
  description: string | null
  status: string
  statusLabel: TodoStatusLabelInfo | null
  priority: string
  dueDate: string | null
  dueTime: string | null
  daysRemaining: number | null
  completedAt: string | null
  completedBy: { id: number; displayName: string } | null
  createdBy: { id: number; displayName: string }
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
  updatedAt: string
}

const todo = ref<PersonalTodoDetail | null>(null)
const loading = ref(true)
const newLabelId = ref<number | null>(null)
const changing = ref(false)

const userId = computed<number | null>(() => authStore.user?.id ?? null)

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getPersonalTodo(todoId)
    todo.value = res.data as unknown as PersonalTodoDetail
    newLabelId.value = todo.value?.statusLabel?.id ?? null
  } catch (e) {
    errorHandler.handleApiError(e, 'personal-todo:load')
    todo.value = null
  } finally {
    loading.value = false
  }
}

async function applyStatusChange() {
  if (!todo.value) return
  if (newLabelId.value === null) return
  if (newLabelId.value === todo.value.statusLabel?.id) return
  changing.value = true
  try {
    await todoApi.changeTodoStatusById('PERSONAL', null, todoId, {
      statusLabelId: newLabelId.value,
    })
    await loadTodo()
    const labelName = todo.value?.statusLabel?.name ?? ''
    notification.success(t('todo.statusChange.success', { name: labelName }))
  } catch (e) {
    errorHandler.handleApiError(e, 'personal-todo:status')
  } finally {
    changing.value = false
  }
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

function formatDateTime(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP')
}

onMounted(loadTodo)
</script>

<template>
  <div v-if="loading" class="space-y-4">
    <Skeleton height="2rem" width="60%" />
    <Skeleton height="8rem" />
    <Skeleton height="4rem" />
  </div>

  <div v-else-if="todo" class="mx-auto max-w-3xl">
    <!-- ヘッダー -->
    <div class="mb-6">
      <BackButton to="/todos" :label="t('todo.backToList')" />
      <PageHeader :title="todo.title" />
    </div>

    <!-- メタ情報 -->
    <div class="mb-6 grid grid-cols-2 gap-4 md:grid-cols-3">
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.status') }}</p>
        <div class="mt-1">
          <TodoStatusLabelBadge :label="todo.statusLabel" :fallback-bucket="todo.status" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.priority') }}</p>
        <div class="mt-1">
          <TodoPriorityBadge :priority="todo.priority" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.dueDate') }}</p>
        <p
          class="mt-1 text-sm font-medium"
          :class="{
            'text-red-500':
              todo.daysRemaining !== null && todo.daysRemaining < 0 && todo.status !== 'COMPLETED',
          }"
        >
          {{ formatDate(todo.dueDate) }}
        </p>
      </div>
    </div>

    <!-- ステータス変更 UI -->
    <SectionCard :title="t('todo.statusChange.section')" class="mb-6">
      <div class="flex items-center gap-3">
        <div class="flex-1 max-w-xs">
          <TodoStatusLabelSelect
            v-if="userId"
            v-model="newLabelId"
            scope-type="PERSONAL"
            :scope-id="userId"
          />
        </div>
        <Button
          :label="t('todo.statusChange.applyButton')"
          :loading="changing"
          :disabled="newLabelId === null || newLabelId === todo.statusLabel?.id"
          @click="applyStatusChange"
        />
      </div>
    </SectionCard>

    <!-- 説明 -->
    <SectionCard v-if="todo.description" :title="t('todo.field.description')" class="mb-6">
      <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">
        {{ todo.description }}
      </p>
    </SectionCard>

    <!-- 完了情報 -->
    <div
      v-if="todo.completedAt"
      class="mb-6 rounded-lg border border-green-200 bg-green-50 p-4 dark:border-green-800 dark:bg-green-900/20"
    >
      <p class="text-sm">
        <i class="pi pi-check-circle mr-1 text-green-600" />
        {{ todo.completedBy?.displayName ?? '-' }} / {{ formatDateTime(todo.completedAt) }}
      </p>
    </div>
  </div>
</template>
