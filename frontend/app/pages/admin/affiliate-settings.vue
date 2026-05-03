<script setup lang="ts">
import type { AffiliateConfigResponse, CreateAffiliateConfigRequest } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const PROVIDERS = [
  { label: 'Amazon', value: 'AMAZON' },
  { label: '楽天', value: 'RAKUTEN' },
  { label: 'Yahoo!ショッピング', value: 'YAHOO' },
  { label: 'その他', value: 'OTHER' },
]

const PLACEMENTS = [
  { label: 'サイドバー右', value: 'SIDEBAR_RIGHT' },
  { label: 'バナーフッター', value: 'BANNER_FOOTER' },
  { label: 'コンテンツ内', value: 'INLINE_CONTENT' },
  { label: 'ヘッダー下', value: 'BELOW_HEADER' },
]

const configs = ref<AffiliateConfigResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<AffiliateConfigResponse | null>(null)
const previewItem = ref<AffiliateConfigResponse | null>(null)
const showPreviewDialog = ref(false)

const defaultForm = (): CreateAffiliateConfigRequest => ({
  provider: 'AMAZON',
  tagId: '',
  placement: 'SIDEBAR_RIGHT',
  description: '',
  bannerImageUrl: '',
  bannerWidth: 300,
  bannerHeight: 250,
  altText: '',
  activeFrom: undefined,
  activeUntil: undefined,
  displayPriority: 0,
})
const form = ref<CreateAffiliateConfigRequest>(defaultForm())

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getAffiliateConfigs()
    configs.value = res.data
  } catch {
    showError('アフィリエイト設定の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = defaultForm()
  showDialog.value = true
}

function openEdit(item: AffiliateConfigResponse) {
  editingItem.value = item
  form.value = {
    provider: item.provider,
    tagId: item.tagId,
    placement: item.placement,
    description: item.description,
    bannerImageUrl: item.bannerImageUrl,
    bannerWidth: item.bannerWidth,
    bannerHeight: item.bannerHeight,
    altText: item.altText,
    activeFrom: item.activeFrom ?? undefined,
    activeUntil: item.activeUntil ?? undefined,
    displayPriority: item.displayPriority,
  }
  showDialog.value = true
}

async function save() {
  try {
    if (editingItem.value) {
      await systemAdminApi.updateAffiliateConfig(editingItem.value.id, form.value)
      success('アフィリエイト設定を更新しました')
    } else {
      await systemAdminApi.createAffiliateConfig(form.value)
      success('アフィリエイト設定を作成しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  }
}

async function toggle(item: AffiliateConfigResponse) {
  try {
    await systemAdminApi.toggleAffiliateConfig(item.id)
    item.isActive = !item.isActive
    success(`${item.tagId} を${item.isActive ? '有効' : '無効'}にしました`)
  } catch {
    showError('切替に失敗しました')
  }
}

async function remove(id: number) {
  try {
    await systemAdminApi.deleteAffiliateConfig(id)
    success('アフィリエイト設定を削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

function providerLabel(value: string) {
  return PROVIDERS.find((p) => p.value === value)?.label ?? value
}

function placementLabel(value: string) {
  return PLACEMENTS.find((p) => p.value === value)?.label ?? value
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="アフィリエイト設定" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <div v-else-if="configs.length === 0" class="py-8 text-center text-surface-500">
      アフィリエイト設定がありません
    </div>

    <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
      <div
        v-for="cfg in configs"
        :key="cfg.id"
        class="relative rounded-lg border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-800"
      >
        <div class="mb-3 flex items-center justify-between">
          <Tag
            :value="providerLabel(cfg.provider)"
            :severity="cfg.provider === 'AMAZON' ? 'warning' : cfg.provider === 'RAKUTEN' ? 'danger' : 'info'"
          />
          <ToggleSwitch :model-value="cfg.isActive" @update:model-value="() => toggle(cfg)" />
        </div>

        <dl class="mb-2 space-y-1 text-sm [&_dd]:inline [&_dt]:inline [&_dt]:font-medium [&_dt]:text-surface-500">
          <div><dt>タグID:</dt> <dd class="font-mono">{{ cfg.tagId }}</dd></div>
          <div><dt>配置:</dt> <dd>{{ placementLabel(cfg.placement) }}</dd></div>
          <div v-if="cfg.description"><dt>説明:</dt> <dd class="truncate">{{ cfg.description }}</dd></div>
          <div><dt>優先度:</dt> <dd>{{ cfg.displayPriority }}</dd></div>
          <div v-if="cfg.activeFrom || cfg.activeUntil">
            <dt>期間:</dt>
            <dd class="text-xs">{{ cfg.activeFrom ? new Date(cfg.activeFrom).toLocaleDateString('ja-JP') : '---' }} 〜 {{ cfg.activeUntil ? new Date(cfg.activeUntil).toLocaleDateString('ja-JP') : '---' }}</dd>
          </div>
        </dl>

        <div class="flex items-center gap-1 border-t border-surface-100 pt-3 dark:border-surface-700">
          <Button
            icon="pi pi-eye"
            size="small"
            severity="secondary"
            text
            @click="previewItem = cfg; showPreviewDialog = true"
          />
          <Button
            icon="pi pi-pencil"
            size="small"
            severity="secondary"
            text
            @click="openEdit(cfg)"
          />
          <Button
            icon="pi pi-trash"
            size="small"
            severity="danger"
            text
            @click="remove(cfg.id)"
          />
          <span class="ml-auto text-xs text-surface-400">
            {{ new Date(cfg.updatedAt).toLocaleString('ja-JP') }}
          </span>
        </div>
      </div>
    </div>

    <!-- Create / Edit Dialog -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? 'アフィリエイト設定編集' : 'アフィリエイト設定作成'"
      :style="{ width: '640px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">プロバイダー</label>
            <Select
              v-model="form.provider"
              :options="PROVIDERS"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">配置</label>
            <Select
              v-model="form.placement"
              :options="PLACEMENTS"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">タグID</label>
          <InputText v-model="form.tagId" class="w-full" placeholder="例: mannschaft-22" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText v-model="form.description" class="w-full" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">バナー画像URL</label>
          <InputText v-model="form.bannerImageUrl" class="w-full" placeholder="https://..." />
        </div>

        <div class="grid grid-cols-3 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">幅 (px)</label>
            <InputNumber v-model="form.bannerWidth" class="w-full" :min="0" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">高さ (px)</label>
            <InputNumber v-model="form.bannerHeight" class="w-full" :min="0" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">表示優先度</label>
            <InputNumber v-model="form.displayPriority" class="w-full" :min="0" />
          </div>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">代替テキスト</label>
          <InputText v-model="form.altText" class="w-full" />
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">開始日</label>
            <InputText v-model="form.activeFrom" type="date" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">終了日</label>
            <InputText v-model="form.activeUntil" type="date" class="w-full" />
          </div>
        </div>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
          <Button :label="editingItem ? '更新' : '作成'" @click="save" />
        </div>
      </template>
    </Dialog>

    <!-- Preview Dialog -->
    <Dialog v-model:visible="showPreviewDialog" header="バナープレビュー" :style="{ width: '520px' }" modal>
      <template v-if="previewItem">
        <p class="mb-3 text-sm text-surface-500">{{ providerLabel(previewItem.provider) }} / {{ placementLabel(previewItem.placement) }}</p>
        <div
          class="flex items-center justify-center rounded border border-dashed border-surface-300 bg-surface-50 p-4 dark:border-surface-600 dark:bg-surface-900"
          :style="{ minHeight: `${previewItem.bannerHeight}px`, maxWidth: `${previewItem.bannerWidth}px` }"
        >
          <img v-if="previewItem.bannerImageUrl" :src="previewItem.bannerImageUrl" :alt="previewItem.altText" :width="previewItem.bannerWidth" :height="previewItem.bannerHeight" class="max-w-full object-contain" >
          <div v-else class="text-center text-surface-400">
            <i class="pi pi-image mb-2 text-3xl" />
            <p class="text-sm">{{ previewItem.bannerWidth }} x {{ previewItem.bannerHeight }}</p>
            <p class="text-xs">画像未設定</p>
          </div>
        </div>
        <p class="mt-3 text-xs text-surface-400">タグID: <span class="font-mono">{{ previewItem.tagId }}</span></p>
      </template>
    </Dialog>
  </div>
</template>
