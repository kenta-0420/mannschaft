<script setup lang="ts">
import type { WallpaperResponse, CreateWallpaperRequest } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const items = ref<WallpaperResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const previewItem = ref<WallpaperResponse | null>(null)
const showPreview = ref(false)
const saving = ref(false)

const form = ref<CreateWallpaperRequest & { startDate?: string; endDate?: string }>({
  name: '',
  imageUrl: '',
  thumbnailUrl: '',
  templateSlug: '',
  category: 'SEASONAL',
  sortOrder: 0,
})

const categoryOptions = [
  { label: 'シーズナル', value: 'SEASONAL' },
  { label: 'イベント', value: 'EVENT' },
  { label: '限定', value: 'LIMITED' },
]

async function load() {
  loading.value = true
  try {
    const res = await systemAdminApi.getTemplateWallpapers()
    items.value = res.data
  } catch {
    showError('壁紙一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function statusLabel(item: WallpaperResponse): string {
  return item.active ? '公開中' : '非公開'
}

function statusSeverity(item: WallpaperResponse): 'success' | 'secondary' {
  return item.active ? 'success' : 'secondary'
}

function openCreate() {
  form.value = {
    name: '',
    imageUrl: '',
    thumbnailUrl: '',
    templateSlug: '',
    category: 'SEASONAL',
    sortOrder: 0,
  }
  showDialog.value = true
}

function openPreview(item: WallpaperResponse) {
  previewItem.value = item
  showPreview.value = true
}

function onFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  const reader = new FileReader()
  reader.onload = (e) => {
    const dataUrl = e.target?.result as string
    form.value.imageUrl = dataUrl
    form.value.thumbnailUrl = dataUrl
  }
  reader.readAsDataURL(file)
}

async function save() {
  if (!form.value.name || !form.value.imageUrl) {
    showError('名前と画像は必須です')
    return
  }
  saving.value = true
  try {
    const body: CreateWallpaperRequest = {
      templateSlug: form.value.templateSlug || undefined,
      name: form.value.name,
      imageUrl: form.value.imageUrl,
      thumbnailUrl: form.value.thumbnailUrl || form.value.imageUrl,
      category: form.value.category,
      sortOrder: form.value.sortOrder,
    }
    await systemAdminApi.createTemplateWallpaper(body)
    success('壁紙を作成しました')
    showDialog.value = false
    await load()
  } catch {
    showError('作成に失敗しました')
  } finally {
    saving.value = false
  }
}

async function toggleActive(item: WallpaperResponse) {
  try {
    if (item.active) {
      await systemAdminApi.deleteTemplateWallpaper(item.id)
      success('壁紙を非公開にしました')
    } else {
      await systemAdminApi.createTemplateWallpaper({
        templateSlug: item.templateSlug,
        name: item.name,
        imageUrl: item.imageUrl,
        thumbnailUrl: item.thumbnailUrl,
        category: item.category,
        sortOrder: item.sortOrder,
      })
      success('壁紙を公開しました')
    }
    await load()
  } catch {
    showError('状態の変更に失敗しました')
  }
}

async function remove(id: number) {
  try {
    await systemAdminApi.deleteTemplateWallpaper(id)
    success('壁紙を削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="シーズナル壁紙管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <div v-else-if="items.length === 0" class="py-12 text-center text-surface-500">
      壁紙が登録されていません
    </div>

    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="item in items"
        :key="item.id"
        class="overflow-hidden rounded-lg border border-surface-200 bg-white shadow-sm dark:border-surface-700 dark:bg-surface-800"
      >
        <div
          class="relative h-40 cursor-pointer bg-surface-100 dark:bg-surface-700"
          @click="openPreview(item)"
        >
          <img
            v-if="item.thumbnailUrl"
            :src="item.thumbnailUrl"
            :alt="item.name"
            class="size-full object-cover"
          >
          <div
            v-else
            class="flex size-full items-center justify-center text-surface-400"
          >
            <i class="pi pi-image text-4xl" />
          </div>
          <Tag
            :value="statusLabel(item)"
            :severity="statusSeverity(item)"
            class="absolute right-2 top-2"
          />
        </div>

        <div class="p-3">
          <h3 class="mb-1 truncate text-sm font-semibold">{{ item.name }}</h3>
          <div class="mb-2 flex items-center gap-2 text-xs text-surface-500">
            <Tag :value="item.category" severity="info" class="text-xs" />
            <span>順序: {{ item.sortOrder }}</span>
          </div>

          <div class="flex items-center justify-between">
            <ToggleSwitch
              :model-value="item.active"
              @update:model-value="() => toggleActive(item)"
            />
            <div class="flex gap-1">
              <Button
                icon="pi pi-eye"
                size="small"
                severity="secondary"
                text
                @click="openPreview(item)"
              />
              <Button
                icon="pi pi-trash"
                size="small"
                severity="danger"
                text
                @click="remove(item.id)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Create Dialog -->
    <Dialog
      v-model:visible="showDialog"
      header="壁紙を作成"
      :style="{ width: '560px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">壁紙名</label>
          <InputText v-model="form.name" class="w-full" placeholder="例: 2026年 春の桜" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">画像アップロード</label>
          <input
            type="file"
            accept="image/*"
            class="block w-full text-sm text-surface-500 file:mr-4 file:rounded file:border-0 file:bg-primary-50 file:px-4 file:py-2 file:text-sm file:font-semibold file:text-primary-700 hover:file:bg-primary-100 dark:file:bg-primary-900 dark:file:text-primary-300"
            @change="onFileSelect"
          >
        </div>

        <div v-if="form.imageUrl" class="overflow-hidden rounded-lg border border-surface-200">
          <img
            :src="form.imageUrl"
            alt="プレビュー"
            class="h-40 w-full object-cover"
          >
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">テンプレートスラッグ</label>
          <InputText v-model="form.templateSlug" class="w-full" placeholder="例: spring-2026" />
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">カテゴリ</label>
            <Select
              v-model="form.category"
              :options="categoryOptions"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">表示順</label>
            <InputNumber v-model="form.sortOrder" class="w-full" :min="0" />
          </div>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="mb-1 block text-sm font-medium">公開開始日</label>
            <DatePicker v-model="form.startDate" class="w-full" date-format="yy-mm-dd" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">公開終了日</label>
            <DatePicker v-model="form.endDate" class="w-full" date-format="yy-mm-dd" />
          </div>
        </div>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
          <Button label="作成" :loading="saving" @click="save" />
        </div>
      </template>
    </Dialog>

    <!-- Preview Dialog -->
    <Dialog
      v-model:visible="showPreview"
      :header="previewItem?.name ?? 'プレビュー'"
      :style="{ width: '720px' }"
      modal
    >
      <div v-if="previewItem" class="flex flex-col gap-4">
        <img
          :src="previewItem.imageUrl"
          :alt="previewItem.name"
          class="w-full rounded-lg object-contain"
        >
        <div class="grid grid-cols-2 gap-2 text-sm">
          <div><span class="font-medium">カテゴリ:</span> {{ previewItem.category }}</div>
          <div><span class="font-medium">スラッグ:</span> {{ previewItem.templateSlug }}</div>
          <div><span class="font-medium">表示順:</span> {{ previewItem.sortOrder }}</div>
          <div>
            <span class="font-medium">状態:</span>
            <Tag
              :value="statusLabel(previewItem)"
              :severity="statusSeverity(previewItem)"
              class="ml-1"
            />
          </div>
        </div>
      </div>
    </Dialog>
  </div>
</template>
