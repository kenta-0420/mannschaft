<script setup lang="ts">
/**
 * 組織 TODO 詳細ページ（F02.3.1 Phase 2 でキャッチボール UI を統合）。
 *
 * Phase 0 で OrgTodoController が main にマージされた後に実機動作する。
 * それまでは本ページの読み込みは getTodo の API 404 で空状態になる想定。
 */
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const orgId = Number(route.params.id)
const todoId = Number(route.params.todoId)
const todoApi = useTodoApi()
const notification = useNotification()

interface TodoDetail {
  id: number
  title: string
  description: string | null
  status: string
  priority: string
  dueDate: string | null
  daysRemaining: number | null
  completedAt: string | null
  completedBy: { id: number; displayName: string } | null
  createdBy: { id: number; displayName: string }
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  createdAt: string
  updatedAt: string
}

const todo = ref<TodoDetail | null>(null)
const loading = ref(true)
const showHandoffDialog = ref(false)
const timelineRef = ref<{ reload: () => void } | null>(null)

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getTodo('organization', orgId, todoId)
    todo.value = res.data as unknown as TodoDetail
  } catch {
    notification.error('TODOの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function formatDate(s: string | null): string {
  if (!s) return '—'
  return new Date(s).toLocaleDateString()
}

function formatDateTime(s: string): string {
  return new Date(s).toLocaleString()
}

onMounted(async () => {
  await loadTodo()
})
</script>

<template>
  <div v-if="loading" class="space-y-4">
    <Skeleton height="2rem" width="60%" />
    <Skeleton height="8rem" />
  </div>

  <div v-else-if="todo" class="mx-auto max-w-3xl">
    <div class="mb-6">
      <BackButton :to="`/organizations/${orgId}/todos`" label="TODO一覧" />
      <div class="flex items-start justify-between">
        <PageHeader :title="todo.title" />
        <div class="flex gap-2">
          <Button
            :label="t('handoff.button.passToOther')"
            icon="pi pi-send"
            severity="info"
            outlined
            size="small"
            @click="showHandoffDialog = true"
          />
        </div>
      </div>
    </div>

    <div class="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">ステータス</p>
        <div class="mt-1">
          <TodoStatusBadge :status="todo.status" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">優先度</p>
        <div class="mt-1">
          <TodoPriorityBadge :priority="todo.priority" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">期限</p>
        <p class="mt-1 text-sm font-medium">{{ formatDate(todo.dueDate) }}</p>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">作成者</p>
        <p class="mt-1 text-sm font-medium">{{ todo.createdBy.displayName }}</p>
      </div>
    </div>

    <SectionCard v-if="todo.description" title="説明" class="mb-6">
      <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">{{ todo.description }}</p>
    </SectionCard>

    <SectionCard title="担当者" class="mb-6">
      <div v-if="todo.assignees.length > 0" class="flex flex-wrap gap-2">
        <div v-for="a in todo.assignees" :key="a.userId" class="flex items-center gap-2 rounded-full bg-surface-100 px-3 py-1 dark:bg-surface-700">
          <Avatar
            :image="a.avatarUrl ?? undefined"
            :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
            shape="circle"
            size="small"
          />
          <span class="text-sm">{{ a.displayName }}</span>
        </div>
      </div>
      <p v-else class="text-sm text-surface-400">担当者未割り当て</p>
    </SectionCard>

    <div v-if="todo.completedAt" class="mb-6 rounded-lg border border-green-200 bg-green-50 p-4 dark:border-green-800 dark:bg-green-900/20">
      <p class="text-sm">
        <i class="pi pi-check-circle mr-1 text-green-600" />
        {{ todo.completedBy?.displayName ?? '不明' }} が {{ formatDateTime(todo.completedAt) }} に完了
      </p>
    </div>

    <!-- F02.3.1 Phase 2 — キャッチボール履歴 -->
    <SectionCard class="mb-6">
      <TodoHandoffTimeline
        ref="timelineRef"
        scope-type="organization"
        :scope-id="orgId"
        :todo-id="todoId"
      />
    </SectionCard>

    <!-- F02.3.1 Phase 2 — キャッチボールダイアログ -->
    <TodoHandoffDialog
      v-model:visible="showHandoffDialog"
      scope-type="organization"
      :scope-id="orgId"
      :todo-id="todoId"
      :todo-title="todo.title"
      @handoff-complete="async () => { await loadTodo(); timelineRef?.reload() }"
    />
  </div>
</template>
