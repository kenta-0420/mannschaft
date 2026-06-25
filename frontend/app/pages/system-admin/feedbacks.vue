<script setup lang="ts">
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'
import Checkbox from 'primevue/checkbox'
import Button from 'primevue/button'
import type { FeedbackResponse } from '~/types/feedback'

// ─── ページメタ ───────────────────────────────────────────────────────────────
definePageMeta({
  layout: 'default',
})

const { t } = useI18n()
const toast = useToast()

// ─── API / Store ─────────────────────────────────────────────────────────────
const { getFeedbacks, respondToFeedback, updateFeedbackStatus } = useSystemAdminFeedbackApi()
const impersonationStore = useAdminImpersonationStore()

// ─── 状態 ─────────────────────────────────────────────────────────────────────
const feedbacks = ref<FeedbackResponse[]>([])
const loading = ref(false)
const totalRecords = ref(0)
const page = ref(0)
const pageSize = ref(20)
const statusFilter = ref<string | null>(null)

// ─── ステータス一覧 ────────────────────────────────────────────────────────────
const statusOptions = computed(() => [
  { label: t('feedback.inbox.filter.all'), value: null },
  { label: t('feedback.status.NEW'), value: 'NEW' },
  { label: t('feedback.status.OPEN'), value: 'OPEN' },
  { label: t('feedback.status.IN_PROGRESS'), value: 'IN_PROGRESS' },
  { label: t('feedback.status.RESPONDED'), value: 'RESPONDED' },
  { label: t('feedback.status.CLOSED'), value: 'CLOSED' },
])

// ─── データ取得 ────────────────────────────────────────────────────────────────
async function load() {
  loading.value = true
  try {
    const res = await getFeedbacks({
      status: statusFilter.value ?? undefined,
      page: page.value,
      size: pageSize.value,
      sort: 'createdAt,desc',
    })
    feedbacks.value = res.data
    // BE の PageMeta#total が正式フィールド名（Java の long total）
    totalRecords.value = res.meta?.total ?? res.meta?.totalElements ?? 0
  }
  catch {
    toast.add({ severity: 'error', summary: t('dialog.error'), life: 3000 })
  }
  finally {
    loading.value = false
  }
}

onMounted(load)

// ─── ページング ────────────────────────────────────────────────────────────────
function onPage(event: { page: number; rows: number }) {
  page.value = event.page
  pageSize.value = event.rows
  load()
}

// ─── ステータスフィルタ変更 ────────────────────────────────────────────────────
watch(statusFilter, () => {
  page.value = 0
  load()
})

// ─── ステータスバッジ ─────────────────────────────────────────────────────────
function statusSeverity(status: string): 'info' | 'warn' | 'success' | 'secondary' {
  switch (status) {
    case 'NEW': return 'info'
    case 'OPEN': return 'info'
    case 'IN_PROGRESS': return 'warn'
    case 'RESPONDED': return 'success'
    case 'CLOSED': return 'secondary'
    default: return 'info'
  }
}

// ─── ステータス変更 ────────────────────────────────────────────────────────────
async function changeStatus(feedback: FeedbackResponse, newStatus: string) {
  try {
    await updateFeedbackStatus(feedback.id, { status: newStatus })
    toast.add({ severity: 'success', summary: t('feedback.inbox.statusChange.success'), life: 3000 })
    await load()
  }
  catch {
    toast.add({ severity: 'error', summary: t('feedback.inbox.statusChange.error'), life: 3000 })
  }
}

// ─── 回答ダイアログ ────────────────────────────────────────────────────────────
const respondDialogVisible = ref(false)
const respondTarget = ref<FeedbackResponse | null>(null)
const adminResponse = ref('')
const isPublicResponse = ref(false)
const respondSubmitting = ref(false)

function openRespondDialog(feedback: FeedbackResponse) {
  respondTarget.value = feedback
  adminResponse.value = feedback.adminResponse ?? ''
  isPublicResponse.value = feedback.isPublicResponse
  respondDialogVisible.value = true
}

async function submitResponse() {
  if (!respondTarget.value) return
  respondSubmitting.value = true
  try {
    await respondToFeedback(respondTarget.value.id, {
      adminResponse: adminResponse.value,
      isPublicResponse: isPublicResponse.value,
    })
    toast.add({ severity: 'success', summary: t('feedback.inbox.respond.success'), life: 3000 })
    respondDialogVisible.value = false
    await load()
  }
  catch {
    toast.add({ severity: 'error', summary: t('feedback.inbox.respond.error'), life: 3000 })
  }
  finally {
    respondSubmitting.value = false
  }
}

// ─── 管理者変身（ユーザー視点確認）────────────────────────────────────────────
function startImpersonation(feedback: FeedbackResponse) {
  if (feedback.isAnonymous || !feedback.submittedBy) return
  const label = `User #${feedback.submittedBy}`
  impersonationStore.startImpersonation(feedback.submittedBy, label)
}
</script>

<template>
  <div class="mx-auto max-w-7xl p-6">
    <!-- ヘッダー -->
    <div class="mb-6">
      <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
        {{ t('feedback.inbox.title') }}
      </h1>
      <p class="mt-1 text-sm text-surface-500">
        {{ t('feedback.inbox.description') }}
      </p>
    </div>

    <!-- フィルタ -->
    <div class="mb-4 flex items-center gap-3">
      <label class="text-sm font-medium text-surface-600 dark:text-surface-300">
        {{ t('feedback.inbox.filter.label') }}
      </label>
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        class="w-48"
      />
    </div>

    <!-- テーブル -->
    <DataTable
      :value="feedbacks"
      :loading="loading"
      :total-records="totalRecords"
      :rows="pageSize"
      :rows-per-page-options="[10, 20, 50]"
      paginator
      lazy
      striped-rows
      class="text-sm"
      @page="onPage"
    >
      <template #empty>
        <div class="py-8 text-center text-surface-400">
          {{ t('feedback.inbox.noData') }}
        </div>
      </template>

      <!-- カテゴリ -->
      <Column field="category" :header="t('feedback.category.label')" class="w-28">
        <template #body="{ data }">
          <span class="text-xs font-medium">{{ t(`feedback.category.${data.category.toLowerCase()}`) }}</span>
        </template>
      </Column>

      <!-- タイトル / 本文 -->
      <Column field="title" :header="t('feedback.title.label')">
        <template #body="{ data }">
          <div>
            <p class="font-medium text-surface-800 dark:text-surface-100">{{ data.title || '（タイトルなし）' }}</p>
            <p class="mt-0.5 line-clamp-2 text-xs text-surface-500">{{ data.body }}</p>
          </div>
        </template>
      </Column>

      <!-- 送信者 -->
      <Column :header="t('feedback.submitter')" class="w-32">
        <template #body="{ data }">
          <span v-if="data.isAnonymous" class="text-xs text-surface-400 italic">
            {{ t('feedback.inbox.anonymous') }}
          </span>
          <span v-else class="text-xs">User #{{ data.submittedBy }}</span>
        </template>
      </Column>

      <!-- 賛同数 -->
      <Column field="voteCount" :header="t('feedback.votes')" class="w-20 text-center">
        <template #body="{ data }">
          <span class="text-sm font-semibold">{{ data.voteCount }}</span>
        </template>
      </Column>

      <!-- ステータス -->
      <Column field="status" :header="t('label.status')" class="w-32">
        <template #body="{ data }">
          <Select
            :model-value="data.status"
            :options="statusOptions.filter(o => o.value !== null)"
            option-label="label"
            option-value="value"
            class="w-full text-xs"
            size="small"
            @update:model-value="changeStatus(data, $event)"
          />
        </template>
      </Column>

      <!-- ステータスバッジ（参考表示） -->
      <Column class="w-28">
        <template #body="{ data }">
          <Tag :severity="statusSeverity(data.status)" :value="t(`feedback.status.${data.status}`)" />
        </template>
      </Column>

      <!-- 作成日 -->
      <Column field="createdAt" :header="t('label.created_at')" class="w-32">
        <template #body="{ data }">
          <span class="text-xs text-surface-500">{{ new Date(data.createdAt).toLocaleDateString('ja-JP') }}</span>
        </template>
      </Column>

      <!-- 操作 -->
      <Column :header="t('label.actions')" class="w-40">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              icon="pi pi-reply"
              size="small"
              :label="t('feedback.inbox.respond.title')"
              outlined
              class="text-xs"
              @click="openRespondDialog(data)"
            />
            <Button
              v-if="!data.isAnonymous && data.submittedBy"
              icon="pi pi-eye"
              size="small"
              :label="t('feedback.inbox.viewAsUser')"
              severity="warn"
              outlined
              class="text-xs"
              @click="startImpersonation(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 回答ダイアログ -->
    <Dialog
      v-model:visible="respondDialogVisible"
      :header="t('feedback.inbox.respond.title')"
      :style="{ width: '40rem' }"
      modal
    >
      <div v-if="respondTarget" class="space-y-4">
        <!-- 元のフィードバック内容（参考表示） -->
        <div class="rounded-lg bg-surface-100 p-3 dark:bg-surface-700">
          <p class="text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ respondTarget.title || '（タイトルなし）' }}
          </p>
          <p class="mt-1 text-sm text-surface-500">{{ respondTarget.body }}</p>
        </div>

        <!-- 回答内容 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('feedback.inbox.respond.adminResponse') }}
          </label>
          <Textarea
            v-model="adminResponse"
            :placeholder="t('feedback.inbox.respond.adminResponsePlaceholder')"
            rows="5"
            class="w-full"
          />
        </div>

        <!-- 公開回答チェックボックス -->
        <div class="flex items-center gap-2">
          <Checkbox v-model="isPublicResponse" binary input-id="publicResponse" />
          <label for="publicResponse" class="text-sm">
            {{ t('feedback.inbox.respond.publicResponse') }}
          </label>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('button.cancel')"
          text
          @click="respondDialogVisible = false"
        />
        <Button
          :label="t('feedback.inbox.respond.submit')"
          icon="pi pi-send"
          :loading="respondSubmitting"
          @click="submitResponse"
        />
      </template>
    </Dialog>
  </div>
</template>
