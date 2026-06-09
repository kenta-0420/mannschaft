<script setup lang="ts">
import type { TodoStatusLabelInfo } from '~/types/todoStatusLabel'

definePageMeta({
  layout: 'organization',
  middleware: 'auth',
})

const { t } = useI18n()
const route = useRoute()
const orgId = String(route.params.id)
const todoId = Number(route.params.todoId)
const todoApi = useTodoApi()
const labelApi = useTodoStatusLabelApi()
const progressApi = useTodoProgress()
const notification = useNotification()
const errorHandler = useErrorHandler()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)
const { formatDate, formatDateTime } = useDatetime()

/** Wave 1 DTO刷新: ネスト構造 */
interface TodoDetail {
  id: number
  content?: {
    title?: string
    description?: string | null
    startDate?: string | null
    progressRate?: string
    progressManual?: boolean
    sortOrder?: number
  }
  schedule?: {
    dueDate?: string | null
    dueTime?: string | null
    daysRemaining?: number | null
    linkedScheduleId?: number | null
  }
  audit?: {
    createdAt?: string
    updatedAt?: string
    createdBy?: { id: number; displayName: string }
    completedBy?: { id: number; displayName: string } | null
  }
  /** @deprecated 旧フラットフィールド互換 — ステータスバケット */
  status?: string
  /** @deprecated 旧フラットフィールド互換 */
  priority?: string
  /** @deprecated 旧フラットフィールド互換 */
  statusLabel?: TodoStatusLabelInfo | null
  /** @deprecated 旧フラットフィールド互換 */
  daysRemaining?: number | null
  /** @deprecated 旧フラットフィールド互換 */
  completedAt?: string | null
  /** @deprecated 旧フラットフィールド互換 */
  completedBy?: { id: number; displayName: string } | null
  /** @deprecated 旧フラットフィールド互換 */
  createdBy?: { id: number; displayName: string }
  assignees: Array<{ userId: number; displayName: string; avatarUrl: string | null }>
  progressRate: string
  progressManual: boolean
}

const todo = ref<TodoDetail | null>(null)
const loading = ref(true)
const showEditDialog = ref(false)

// F02.3.1 Phase 2 — キャッチボール
const showHandoffDialog = ref(false)
const timelineRef = ref<{ reload: () => void } | null>(null)

type DetailTab = 'progress' | 'shared_memo' | 'personal_memo'
const activeDetailTab = ref<DetailTab>('progress')

// ステータス変更（Select + 「変更」ボタン + 確認ダイアログ）
const newLabelId = ref<number | null>(null)
const confirmDialogVisible = ref(false)
const changing = ref(false)
const toLabelName = ref('')

const fromLabelName = computed(() => todo.value?.statusLabel?.name ?? '')

async function loadTodo() {
  loading.value = true
  try {
    const res = await todoApi.getTodo('organization', orgId, todoId)
    const data = res.data as unknown as TodoDetail & {
      progressRate?: string
      progressManual?: boolean
    }
    todo.value = {
      ...data,
      progressRate: data.content?.progressRate ?? data.progressRate ?? '0.00',
      progressManual: data.content?.progressManual ?? data.progressManual ?? false,
    }
    newLabelId.value = data.statusLabel?.id ?? null
  } catch {
    notification.error(t('todo.detail.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function openConfirmDialog() {
  if (!todo.value || newLabelId.value === null) return
  if (newLabelId.value === todo.value.statusLabel?.id) return
  try {
    const res = await labelApi.listLabels('organization', orgId)
    const target = res.data.find((l) => l.id === newLabelId.value)
    toLabelName.value = target?.name ?? ''
    confirmDialogVisible.value = true
  } catch (e) {
    errorHandler.handleApiError(e, 'org-todo:status-confirm')
  }
}

async function applyStatusChange() {
  if (!todo.value || newLabelId.value === null) return
  changing.value = true
  try {
    await todoApi.changeTodoStatus('organization', orgId, todoId, {
      statusLabelId: newLabelId.value,
    })
    confirmDialogVisible.value = false
    await loadTodo()
    notification.success(
      t('todo.statusChange.success', { name: todo.value?.statusLabel?.name ?? '' }),
    )
  } catch (e) {
    errorHandler.handleApiError(e, 'org-todo:status')
  } finally {
    changing.value = false
  }
}

async function onProgressRateUpdate(rate: string) {
  if (!todo.value) return
  try {
    await progressApi.updateProgress('organization', orgId, todoId, { progressRate: rate })
    todo.value.progressRate = rate
    if (todo.value.content) {
      todo.value.content.progressRate = rate
    }
  } catch {
    notification.error(t('todo.progress.updateRateFailed'))
  }
}

async function onProgressManualUpdate(manual: boolean) {
  if (!todo.value) return
  try {
    await progressApi.updateProgressMode('organization', orgId, todoId, { progressManual: manual })
    todo.value.progressManual = manual
    if (todo.value.content) {
      todo.value.content.progressManual = manual
    }
    if (!manual) {
      await loadTodo()
    }
  } catch {
    notification.error(t('todo.progress.updateModeFailed'))
  }
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
      <BackButton :to="`/organizations/${orgId}/todos`" :label="t('todo.backToList')" />
      <div class="flex items-start justify-between">
        <PageHeader :title="todo.content?.title ?? ''" />
        <div class="flex gap-2">
          <Button
            :label="t('handoff.button.passToOther')"
            icon="pi pi-send"
            severity="info"
            outlined
            size="small"
            @click="showHandoffDialog = true"
          />
          <Button
            v-if="isAdminOrDeputy"
            :label="t('todo.detail.editButton')"
            icon="pi pi-pencil"
            outlined
            size="small"
            @click="showEditDialog = true"
          />
        </div>
      </div>
    </div>

    <!-- メタ情報 -->
    <div class="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.status') }}</p>
        <div class="mt-1">
          <TodoStatusLabelBadge :label="todo.statusLabel" :fallback-bucket="todo.status" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.priority') }}</p>
        <div class="mt-1">
          <TodoPriorityBadge :priority="todo.priority ?? ''" />
        </div>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.dueDate') }}</p>
        <p
          class="mt-1 text-sm font-medium"
          :class="{
            'text-red-500':
              todo.schedule?.daysRemaining !== null &&
              (todo.schedule?.daysRemaining ?? 0) < 0 &&
              todo.status !== 'COMPLETED',
          }"
        >
          {{ formatDate(todo.schedule?.dueDate ?? null) }}
        </p>
      </div>
      <div class="rounded-lg border border-surface-400 p-3 dark:border-surface-600">
        <p class="text-xs text-surface-500">{{ t('todo.field.creator') }}</p>
        <p class="mt-1 text-sm font-medium">{{ todo.audit?.createdBy?.displayName ?? todo.createdBy?.displayName }}</p>
      </div>
    </div>

    <!-- ステータス変更（Select + 「変更」ボタン、確認ダイアログ付き） -->
    <SectionCard :title="t('todo.statusChange.section')" class="mb-6">
      <div class="flex items-center gap-3">
        <div class="max-w-xs flex-1">
          <TodoStatusLabelSelect
            v-model="newLabelId"
            scope-type="ORGANIZATION"
            :scope-id="orgId"
          />
        </div>
        <Button
          :label="t('todo.statusChange.applyButton')"
          :disabled="newLabelId === null || newLabelId === todo.statusLabel?.id"
          @click="openConfirmDialog"
        />
      </div>
    </SectionCard>

    <!-- 説明 -->
    <SectionCard v-if="todo.content?.description" :title="t('todo.field.description')" class="mb-6">
      <p class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">
        {{ todo.content?.description }}
      </p>
    </SectionCard>

    <!-- 担当者 -->
    <SectionCard :title="t('todo.detail.assigneesSection')" class="mb-6">
      <div v-if="todo.assignees.length > 0" class="flex flex-wrap gap-2">
        <div
          v-for="a in todo.assignees"
          :key="a.userId"
          class="flex items-center gap-2 rounded-full bg-surface-100 px-3 py-1 dark:bg-surface-700"
        >
          <Avatar
            :image="a.avatarUrl ?? undefined"
            :label="a.avatarUrl ? undefined : a.displayName.charAt(0)"
            shape="circle"
            size="small"
          />
          <span class="text-sm">{{ a.displayName }}</span>
        </div>
      </div>
      <p v-else class="text-sm text-surface-400">{{ t('todo.detail.noAssignees') }}</p>
    </SectionCard>

    <!-- 完了情報 -->
    <div
      v-if="todo.completedAt"
      class="mb-6 rounded-lg border border-green-200 bg-green-50 p-4 dark:border-green-800 dark:bg-green-900/20"
    >
      <p class="text-sm">
        <i class="pi pi-check-circle mr-1 text-green-600" />
        {{ t('todo.detail.completedByAt', { name: todo.completedBy?.displayName ?? t('todo.detail.unknownUser'), at: formatDateTime(todo.completedAt) }) }}
      </p>
    </div>

    <!-- 拡張タブ（進捗 / 共有メモ / 個人メモ） -->
    <SectionCard class="mb-6">
      <div
        class="mb-4 flex w-fit gap-1 rounded-lg border border-surface-300 bg-surface-100 p-1 dark:border-surface-600 dark:bg-surface-700"
      >
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="
            activeDetailTab === 'progress'
              ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'
          "
          @click="activeDetailTab = 'progress'"
        >
          <i class="pi pi-chart-bar mr-1" />{{ t('todo.enhancement.progress.tab_label') }}
        </button>
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="
            activeDetailTab === 'shared_memo'
              ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'
          "
          @click="activeDetailTab = 'shared_memo'"
        >
          <i class="pi pi-comments mr-1" />{{ t('todo.enhancement.shared_memo.title') }}
        </button>
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-sm font-medium transition-colors"
          :class="
            activeDetailTab === 'personal_memo'
              ? 'bg-surface-0 text-primary shadow-sm dark:bg-surface-800'
              : 'text-surface-500 hover:text-surface-700 dark:text-surface-400'
          "
          @click="activeDetailTab = 'personal_memo'"
        >
          <i class="pi pi-lock mr-1" />{{ t('todo.enhancement.personal_memo.title') }}
        </button>
      </div>

      <div v-if="activeDetailTab === 'progress'">
        <TodoProgressControl
          :progress-rate="todo.progressRate"
          :progress-manual="todo.progressManual"
          @update:progress-rate="onProgressRateUpdate"
          @update:progress-manual="onProgressManualUpdate"
        />
      </div>

      <div v-else-if="activeDetailTab === 'shared_memo'">
        <TodoSharedMemo scope-type="organization" :scope-id="orgId" :todo-id="todoId" />
      </div>

      <div v-else-if="activeDetailTab === 'personal_memo'">
        <TodoPersonalMemo scope-type="organization" :scope-id="orgId" :todo-id="todoId" />
      </div>
    </SectionCard>

    <!-- コメント -->
    <SectionCard>
      <TodoComments scope-type="organization" :scope-id="orgId" :todo-id="todoId" />
    </SectionCard>

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
      :todo-title="todo.content?.title ?? ''"
      @handoff-complete="async () => { await loadTodo(); timelineRef?.reload() }"
    />

    <!-- 編集ダイアログ -->
    <TodoForm
      v-model:visible="showEditDialog"
      scope-type="organization"
      :scope-id="orgId"
      :todo-id="todoId"
      @saved="loadTodo"
    />

    <!-- ステータス変更確認ダイアログ -->
    <Dialog
      v-model:visible="confirmDialogVisible"
      modal
      :header="t('todo.statusChange.confirmTitle')"
      class="w-full max-w-md"
    >
      <p class="text-sm text-surface-700 dark:text-surface-300">
        {{ t('todo.statusChange.confirmBody', { from: fromLabelName, to: toLabelName }) }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-3">
          <Button
            :label="t('todo.statusChange.cancelButton')"
            severity="secondary"
            text
            @click="confirmDialogVisible = false"
          />
          <Button
            :label="t('todo.statusChange.confirmButton')"
            :loading="changing"
            @click="applyStatusChange"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
