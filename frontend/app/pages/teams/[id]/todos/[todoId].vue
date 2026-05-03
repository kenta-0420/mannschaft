<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)
const todoId = Number(route.params.todoId)
const todoApi = useTodoApi()
const progressApi = useTodoProgress()
const notification = useNotification()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

interface TodoDetail {
  id: number
  title: string
  description: string | null
  status: string
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
  progressRate: string
  progressManual: boolean
}

const todo = ref<TodoDetail | null>(null)
const loading = ref(true)
const showEditDialog = ref(false)

// 拡張タブ: 'progress' | 'shared_memo' | 'personal_memo'
type DetailTab = 'progress' | 'shared_memo' | 'personal_memo'
const activeDetailTab = ref<DetailTab>('progress')

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getTodo('team', teamId, todoId)
    // progressRate / progressManual が存在しない場合はデフォルト値を設定
    const data = res.data as unknown as TodoDetail & { progressRate?: string; progressManual?: boolean }
    todo.value = {
      ...data,
      progressRate: data.progressRate ?? '0.00',
      progressManual: data.progressManual ?? false,
    }
  }
  catch {
    notification.error('TODOの取得に失敗しました')
  }
  finally {
    loading.value = false
  }
}

async function changeStatus(newStatus: string) {
  try {
    await todoApi.changeTodoStatus('team', teamId, todoId, newStatus)
    notification.success('ステータスを変更しました')
    await loadTodo()
  }
  catch {
    notification.error('ステータス変更に失敗しました')
  }
}

async function onProgressRateUpdate(rate: string) {
  if (!todo.value) return
  try {
    await progressApi.updateProgress('team', teamId, todoId, { progressRate: rate })
    todo.value.progressRate = rate
  } catch {
    notification.error('進捗率の更新に失敗しました')
  }
}

async function onProgressManualUpdate(manual: boolean) {
  if (!todo.value) return
  try {
    await progressApi.updateProgressMode('team', teamId, todoId, { progressManual: manual })
    todo.value.progressManual = manual
    if (!manual) {
      // 自動モードに切り替えた場合は最新値を再取得
      await loadTodo()
    }
  } catch {
    notification.error('進捗モードの切替に失敗しました')
  }
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleDateString('ja-JP')
}

function formatDateTime(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP')
}

onMounted(async () => {
  await Promise.all([loadTodo(), loadPermissions()])
})
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
      <BackButton :to="`/teams/${teamId}/todos`" label="TODO一覧" />
      <div class="flex items-start justify-between">
        <PageHeader :title="todo.title" />
        <div class="flex gap-2">
          <Button v-if="isAdminOrDeputy" label="編集" icon="pi pi-pencil" outlined size="small" @click="showEditDialog = true" />
        </div>
      </div>
    </div>

    <!-- メタ情報 -->
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
        <p class="mt-1 text-sm font-medium" :class="{ 'text-red-500': todo.daysRemaining !== null && todo.daysRemaining < 0 && todo.status !== 'COMPLETED' }">
          {{ formatDate(todo.dueDate) }}
        </p>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">作成者</p>
        <p class="mt-1 text-sm font-medium">{{ todo.createdBy.displayName }}</p>
      </div>
    </div>

    <!-- ステータス変更ボタン -->
    <div class="mb-6 flex gap-2">
      <Button v-if="todo.status !== 'OPEN'" label="未着手に戻す" size="small" severity="secondary" outlined @click="changeStatus('OPEN')" />
      <Button v-if="todo.status !== 'IN_PROGRESS'" label="進行中にする" size="small" severity="info" outlined @click="changeStatus('IN_PROGRESS')" />
      <Button v-if="todo.status !== 'COMPLETED'" label="完了にする" size="small" severity="success" @click="changeStatus('COMPLETED')" />
    </div>

    <!-- 説明 -->
    <SectionCard v-if="todo.description" title="説明" class="mb-6">
      <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">{{ todo.description }}</p>
    </SectionCard>

    <!-- 担当者 -->
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

    <!-- 完了情報 -->
    <div v-if="todo.completedAt" class="mb-6 rounded-lg border border-green-200 bg-green-50 p-4 dark:border-green-800 dark:bg-green-900/20">
      <p class="text-sm">
        <i class="pi pi-check-circle mr-1 text-green-600" />
        {{ todo.completedBy?.displayName ?? '不明' }} が {{ formatDateTime(todo.completedAt) }} に完了
      </p>
    </div>

    <!-- 拡張タブ（進捗 / 共有メモ / 個人メモ） -->
    <SectionCard class="mb-6">
      <!-- タブヘッダー -->
      <div class="mb-4 flex gap-1 rounded-lg border border-surface-300 bg-surface-100 p-1 dark:border-surface-600 dark:bg-surface-700 w-fit">
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="activeDetailTab === 'progress'
            ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
          @click="activeDetailTab = 'progress'"
        >
          <i class="pi pi-chart-bar mr-1" />{{ t('todo.enhancement.progress.tab_label') }}
        </button>
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="activeDetailTab === 'shared_memo'
            ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
          @click="activeDetailTab = 'shared_memo'"
        >
          <i class="pi pi-comments mr-1" />{{ t('todo.enhancement.shared_memo.title') }}
        </button>
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="activeDetailTab === 'personal_memo'
            ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
            : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'"
          @click="activeDetailTab = 'personal_memo'"
        >
          <i class="pi pi-lock mr-1" />{{ t('todo.enhancement.personal_memo.title') }}
        </button>
      </div>

      <!-- タブコンテンツ -->
      <div v-if="activeDetailTab === 'progress'">
        <TodoProgressControl
          :progress-rate="todo.progressRate"
          :progress-manual="todo.progressManual"
          @update:progress-rate="onProgressRateUpdate"
          @update:progress-manual="onProgressManualUpdate"
        />
      </div>

      <div v-else-if="activeDetailTab === 'shared_memo'">
        <TodoSharedMemo
          scope-type="team"
          :scope-id="teamId"
          :todo-id="todoId"
        />
      </div>

      <div v-else-if="activeDetailTab === 'personal_memo'">
        <TodoPersonalMemo
          scope-type="team"
          :scope-id="teamId"
          :todo-id="todoId"
        />
      </div>
    </SectionCard>

    <!-- コメント -->
    <SectionCard>
      <TodoComments scope-type="team" :scope-id="teamId" :todo-id="todoId" />
    </SectionCard>

    <!-- 編集ダイアログ -->
    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="team"
      :scope-id="teamId"
      :todo-id="todoId"
      @saved="loadTodo"
    />
  </div>
</template>
