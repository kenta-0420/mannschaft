<script setup lang="ts">
import type { PromotionResponse, PromotionStatus } from '~/types/promotion'

definePageMeta({ middleware: 'auth' })
const route = useRoute()

const { getPromotions, createPromotion, publishPromotion, cancelPromotion, deletePromotion } =
  usePromotionApi()
const { success, error: showError } = useNotification()

// スコープ設定（クエリパラメータから取得、なければデフォルト）
const scopeType = ref<'team' | 'organization'>(
  (route.query.scopeType as 'team' | 'organization') || 'team',
)
const scopeIdInput = ref(route.query.scopeId ? String(route.query.scopeId) : '')
const scopeId = computed(() => Number(scopeIdInput.value) || 0)

const scopeTypeOptions = [
  { label: 'チーム', value: 'team' },
  { label: '組織', value: 'organization' },
]

const promotions = ref<PromotionResponse[]>([])
const loading = ref(false)

// 新規作成ダイアログ
const showCreateDialog = ref(false)
const createForm = ref({
  title: '',
  body: '',
  scheduledAt: '',
  expiresAt: '',
})
const createSubmitting = ref(false)

async function load() {
  if (!scopeId.value) return
  loading.value = true
  try {
    const res = await getPromotions(scopeType.value, scopeId.value)
    promotions.value = res.data
  } catch {
    showError('プロモーション一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handlePublish(id: number) {
  try {
    await publishPromotion(scopeType.value, scopeId.value, id)
    success('公開しました')
    load()
  } catch {
    showError('公開に失敗しました')
  }
}

async function handleCancel(id: number) {
  try {
    await cancelPromotion(scopeType.value, scopeId.value, id)
    success('キャンセルしました')
    load()
  } catch {
    showError('キャンセルに失敗しました')
  }
}

async function handleDelete(id: number) {
  try {
    await deletePromotion(scopeType.value, scopeId.value, id)
    success('削除しました')
    load()
  } catch {
    showError('削除に失敗しました')
  }
}

function openCreateDialog() {
  createForm.value = { title: '', body: '', scheduledAt: '', expiresAt: '' }
  showCreateDialog.value = true
}

async function submitCreate() {
  if (!createForm.value.title) return
  createSubmitting.value = true
  try {
    await createPromotion(scopeType.value, scopeId.value, {
      title: createForm.value.title,
      body: createForm.value.body || null,
      scheduledAt: createForm.value.scheduledAt || null,
      expiresAt: createForm.value.expiresAt || null,
    })
    success('プロモーションを作成しました')
    showCreateDialog.value = false
    load()
  } catch {
    showError('プロモーションの作成に失敗しました')
  } finally {
    createSubmitting.value = false
  }
}

function statusSeverity(status: PromotionStatus): string {
  switch (status) {
    case 'DRAFT': return 'secondary'
    case 'PENDING_APPROVAL': return 'warning'
    case 'APPROVED': return 'info'
    case 'PUBLISHED': return 'success'
    case 'CANCELLED': return 'danger'
    default: return 'secondary'
  }
}

function statusLabel(status: PromotionStatus): string {
  switch (status) {
    case 'DRAFT': return '下書き'
    case 'PENDING_APPROVAL': return '承認待ち'
    case 'APPROVED': return '承認済'
    case 'PUBLISHED': return '公開中'
    case 'CANCELLED': return 'キャンセル'
    default: return status
  }
}

watch([scopeType, scopeIdInput], () => {
  if (scopeId.value) load()
})

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <PageHeader title="プロモーション管理" />
      <div class="ml-auto flex flex-wrap items-center gap-2">
        <!-- スコープ選択 -->
        <Select
          v-model="scopeType"
          :options="scopeTypeOptions"
          option-label="label"
          option-value="value"
          placeholder="対象種別"
          class="w-36"
        />
        <InputText
          v-model="scopeIdInput"
          type="number"
          placeholder="スコープID"
          class="w-32"
        />
        <Button label="読み込む" icon="pi pi-refresh" severity="secondary" outlined @click="load" />
        <Button label="新規作成" icon="pi pi-plus" :disabled="!scopeId" @click="openCreateDialog" />
      </div>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <DataTable
      v-else
      :value="promotions"
      data-key="id"
      striped-rows
    >
      <template #empty>
        <DashboardEmptyState icon="pi pi-megaphone" message="プロモーションがありません" />
      </template>

      <Column field="title" header="タイトル" />

      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>

      <Column header="対象数" style="width: 90px">
        <template #body="{ data }">
          <span class="text-sm">{{ data.recipientCount.toLocaleString('ja-JP') }}</span>
        </template>
      </Column>

      <Column header="開始日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ data.scheduledAt ? new Date(data.scheduledAt).toLocaleDateString('ja-JP') : '-' }}
          </span>
        </template>
      </Column>

      <Column header="終了日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ data.expiresAt ? new Date(data.expiresAt).toLocaleDateString('ja-JP') : '-' }}
          </span>
        </template>
      </Column>

      <Column header="操作" style="width: 260px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              v-if="data.status === 'DRAFT' || data.status === 'APPROVED'"
              label="公開"
              size="small"
              severity="success"
              @click="handlePublish(data.id)"
            />
            <Button
              v-if="data.status === 'PUBLISHED'"
              label="キャンセル"
              size="small"
              severity="warning"
              outlined
              @click="handleCancel(data.id)"
            />
            <Button
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              v-tooltip="'削除'"
              @click="handleDelete(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 新規作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      header="プロモーションを作成"
      :style="{ width: '520px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
          <InputText
            v-model="createForm.title"
            class="w-full"
            placeholder="例: 春の特別キャンペーン"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明・本文</label>
          <Textarea
            v-model="createForm.body"
            class="w-full"
            rows="4"
            placeholder="プロモーションの詳細を入力してください"
          />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日時</label>
            <InputText
              v-model="createForm.scheduledAt"
              type="datetime-local"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日時</label>
            <InputText
              v-model="createForm.expiresAt"
              type="datetime-local"
              class="w-full"
            />
          </div>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showCreateDialog = false" />
        <Button
          label="作成する"
          icon="pi pi-check"
          :loading="createSubmitting"
          :disabled="!createForm.title"
          @click="submitCreate"
        />
      </template>
    </Dialog>
  </div>
</template>
