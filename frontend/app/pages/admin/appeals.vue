<script setup lang="ts">
import type { ModerationAppeal } from '~/types/moderation'

definePageMeta({ middleware: 'auth' })

const { getAppeals, reviewAppeal } = useModerationApi()
const { error: showError, success: showSuccess } = useNotification()

const appeals = ref<ModerationAppeal[]>([])
const loading = ref(false)
const statusFilter = ref<string | undefined>(undefined)

// ダイアログ
const dialogVisible = ref(false)
const dialogAction = ref<'ACCEPTED' | 'REJECTED'>('ACCEPTED')
const selectedAppeal = ref<ModerationAppeal | null>(null)
const reviewNote = ref('')
const submitting = ref(false)

const statusOptions = [
  { label: 'すべて', value: undefined },
  { label: '未対応', value: 'PENDING' },
  { label: '承認済み', value: 'ACCEPTED' },
  { label: '却下済み', value: 'REJECTED' },
]

async function load() {
  loading.value = true
  try {
    const res = await getAppeals({ status: statusFilter.value })
    appeals.value = res.data
  } catch {
    showError('異議申立て一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openDialog(appeal: ModerationAppeal, action: 'ACCEPTED' | 'REJECTED') {
  selectedAppeal.value = appeal
  dialogAction.value = action
  reviewNote.value = ''
  dialogVisible.value = true
}

async function submitReview() {
  if (!selectedAppeal.value) return
  submitting.value = true
  try {
    await reviewAppeal(selectedAppeal.value.id, {
      status: dialogAction.value,
      reviewNote: reviewNote.value,
    })
    showSuccess(dialogAction.value === 'ACCEPTED' ? '承認しました' : '却下しました')
    dialogVisible.value = false
    await load()
  } catch {
    showError('審査の処理に失敗しました')
  } finally {
    submitting.value = false
  }
}

function getStatusSeverity(status: string) {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return 'secondary'
  }
}

function getStatusLabel(status: string) {
  switch (status) {
    case 'PENDING':
      return '未対応'
    case 'ACCEPTED':
      return '承認済み'
    case 'REJECTED':
      return '却下済み'
    default:
      return status
  }
}

watch(statusFilter, () => load())
onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">異議申立て管理</h1>
      <Select
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        placeholder="ステータス"
        class="w-36"
      />
    </div>

    <DataTable
      :value="appeals"
      :loading="loading"
      data-key="id"
      striped-rows
      paginator
      :rows="20"
    >
      <template #empty>
        <div class="py-12 text-center">
          <i class="pi pi-inbox mb-3 text-4xl text-surface-300" />
          <p class="text-surface-400">異議申立てはありません</p>
        </div>
      </template>

      <Column field="id" header="ID" style="width: 80px" />

      <Column header="通報対象" style="width: 120px">
        <template #body="{ data }">
          <span class="text-sm">違反 #{{ data.violationId }}</span>
        </template>
      </Column>

      <Column header="申請者">
        <template #body="{ data }">
          <span class="text-sm font-medium">{{ data.displayName }}</span>
          <span class="ml-1 text-xs text-surface-400">(ID: {{ data.userId }})</span>
        </template>
      </Column>

      <Column header="理由">
        <template #body="{ data }">
          <span class="line-clamp-2 max-w-xs text-sm">{{ data.appealText }}</span>
        </template>
      </Column>

      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="getStatusLabel(data.status)" :severity="getStatusSeverity(data.status)" />
        </template>
      </Column>

      <Column header="作成日" style="width: 160px">
        <template #body="{ data }">
          <span class="text-sm">{{ new Date(data.createdAt).toLocaleString('ja-JP') }}</span>
        </template>
      </Column>

      <Column header="操作" style="width: 160px">
        <template #body="{ data }">
          <div v-if="data.status === 'PENDING'" class="flex gap-2">
            <Button
              label="承認"
              icon="pi pi-check"
              size="small"
              severity="success"
              @click="openDialog(data, 'ACCEPTED')"
            />
            <Button
              label="却下"
              icon="pi pi-times"
              size="small"
              severity="danger"
              @click="openDialog(data, 'REJECTED')"
            />
          </div>
          <span v-else class="text-xs text-surface-400">対応済み</span>
        </template>
      </Column>
    </DataTable>

    <!-- 承認/却下ダイアログ -->
    <Dialog
      v-model:visible="dialogVisible"
      :header="dialogAction === 'ACCEPTED' ? '異議申立てを承認' : '異議申立てを却下'"
      modal
      style="width: 460px"
    >
      <div v-if="selectedAppeal" class="flex flex-col gap-4">
        <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
          <p class="mb-1 text-xs font-medium text-surface-400">申立て内容</p>
          <p class="text-sm">{{ selectedAppeal.appealText }}</p>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">審査コメント（任意）</label>
          <Textarea
            v-model="reviewNote"
            placeholder="審査理由を入力してください"
            rows="3"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          label="キャンセル"
          severity="secondary"
          :disabled="submitting"
          @click="dialogVisible = false"
        />
        <Button
          :label="dialogAction === 'ACCEPTED' ? '承認する' : '却下する'"
          :severity="dialogAction === 'ACCEPTED' ? 'success' : 'danger'"
          :loading="submitting"
          @click="submitReview"
        />
      </template>
    </Dialog>
  </div>
</template>
