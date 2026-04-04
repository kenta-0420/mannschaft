<script setup lang="ts">
import type { FeedbackResponse } from '~/types/admin-report'

definePageMeta({ middleware: 'auth' })

const adminReportApi = useAdminReportApi()
const { success, error: showError } = useNotification()

const feedbacks = ref<FeedbackResponse[]>([])
const loading = ref(true)
const statusFilter = ref<string | undefined>(undefined)
const showRespondDialog = ref(false)
const selectedFeedback = ref<FeedbackResponse | null>(null)
const respondForm = ref({ adminResponse: '', isPublicResponse: false })
const showStatusDialog = ref(false)
const newStatus = ref('')

const statusOptions = [
  { label: 'すべて', value: undefined },
  { label: '新規', value: 'NEW' },
  { label: '対応中', value: 'IN_PROGRESS' },
  { label: '回答済み', value: 'RESPONDED' },
  { label: 'クローズ', value: 'CLOSED' },
]

async function load() {
  loading.value = true
  try {
    const res = await adminReportApi.getFeedbacks({ status: statusFilter.value })
    feedbacks.value = res.data
  } catch {
    showError('フィードバック一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openRespond(fb: FeedbackResponse) {
  selectedFeedback.value = fb
  respondForm.value = {
    adminResponse: fb.adminResponse ?? '',
    isPublicResponse: fb.isPublicResponse,
  }
  showRespondDialog.value = true
}

async function respond() {
  if (!selectedFeedback.value) return
  try {
    await adminReportApi.respondFeedback(selectedFeedback.value.id, respondForm.value)
    success('回答しました')
    showRespondDialog.value = false
    await load()
  } catch {
    showError('回答に失敗しました')
  }
}

function openStatusChange(fb: FeedbackResponse) {
  selectedFeedback.value = fb
  newStatus.value = fb.status
  showStatusDialog.value = true
}

async function changeStatus() {
  if (!selectedFeedback.value) return
  try {
    await adminReportApi.updateFeedbackStatus(selectedFeedback.value.id, {
      status: newStatus.value,
    })
    success('ステータスを変更しました')
    showStatusDialog.value = false
    await load()
  } catch {
    showError('ステータス変更に失敗しました')
  }
}

function statusSeverity(status: string) {
  switch (status) {
    case 'NEW':
      return 'info'
    case 'IN_PROGRESS':
      return 'warn'
    case 'RESPONDED':
      return 'success'
    case 'CLOSED':
      return 'secondary'
    default:
      return 'secondary'
  }
}

watch(statusFilter, () => load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <h1 class="text-2xl font-bold">フィードバック管理</h1>
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-40"
      />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="feedbacks" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">フィードバックがありません</div>
      </template>
      <Column field="category" header="カテゴリ" style="width: 100px" />
      <Column field="title" header="タイトル" />
      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="data.status" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="投票" style="width: 80px">
        <template #body="{ data }">
          <span class="font-medium">{{ data.voteCount }}</span>
        </template>
      </Column>
      <Column header="匿名" style="width: 80px">
        <template #body="{ data }">
          <i
            :class="
              data.isAnonymous ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-300'
            "
          />
        </template>
      </Column>
      <Column header="受信日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 200px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button label="回答" size="small" @click="openRespond(data)" />
            <Button
              label="ステータス"
              size="small"
              severity="secondary"
              @click="openStatusChange(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 回答ダイアログ -->
    <Dialog
      v-model:visible="showRespondDialog"
      header="フィードバック回答"
      :style="{ width: '600px' }"
      modal
    >
      <div v-if="selectedFeedback" class="flex flex-col gap-4">
        <div class="rounded border border-surface-200 bg-surface-50 p-3">
          <p class="text-xs text-surface-500">{{ selectedFeedback.category }}</p>
          <p class="font-medium">{{ selectedFeedback.title }}</p>
          <p class="mt-1 text-sm">{{ selectedFeedback.body }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">回答</label>
          <Textarea v-model="respondForm.adminResponse" rows="4" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="respondForm.isPublicResponse" :binary="true" input-id="isPublic" />
          <label for="isPublic" class="text-sm">公開回答にする</label>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showRespondDialog = false" />
          <Button label="回答する" @click="respond" />
        </div>
      </template>
    </Dialog>

    <!-- ステータス変更ダイアログ -->
    <Dialog
      v-model:visible="showStatusDialog"
      header="ステータス変更"
      :style="{ width: '400px' }"
      modal
    >
      <div class="mb-4">
        <label class="mb-1 block text-sm font-medium">新しいステータス</label>
        <Select
          v-model="newStatus"
          :options="[
            { label: '新規', value: 'NEW' },
            { label: '対応中', value: 'IN_PROGRESS' },
            { label: '回答済み', value: 'RESPONDED' },
            { label: 'クローズ', value: 'CLOSED' },
          ]"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showStatusDialog = false" />
          <Button label="変更する" @click="changeStatus" />
        </div>
      </template>
    </Dialog>
  </div>
</template>
