<script setup lang="ts">
import type { AdCreativeResponse, CreateAdCreativeRequest, UpdateAdCreativeRequest } from '~/types/advertiser'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const toast = useToast()
const confirm = useConfirm()
const advertiserApi = useAdvertiserApi()
const { formatDate } = useDatetime()

const orgSlug = String(route.params.slug)
// クエリパラメータからキャンペーンIDを取得
const campaignId = Number(route.query.campaignId)

const loading = ref(true)
const submitting = ref(false)
const creatives = ref<AdCreativeResponse[]>([])

// ダイアログ表示制御
const showDialog = ref(false)
const editingCreative = ref<AdCreativeResponse | null>(null)

// フォームの状態
const form = ref<CreateAdCreativeRequest>({
  title: '',
  imageUrl: '',
  destinationUrl: '',
})

const statusSeverityMap: Record<string, 'success' | 'info' | 'warn' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  ACTIVE: 'success',
  PAUSED: 'warn',
  ENDED: 'danger',
}

async function load() {
  loading.value = true
  try {
    const res = await advertiserApi.listCreatives('ORGANIZATION', orgSlug, campaignId)
    creatives.value = res.data
  }
  catch {
    toast.add({ severity: 'error', summary: t('advertising.creative.error_load'), life: 3000 })
  }
  finally {
    loading.value = false
  }
}

function openCreateDialog() {
  editingCreative.value = null
  form.value = { title: '', imageUrl: '', destinationUrl: '' }
  showDialog.value = true
}

function openEditDialog(creative: AdCreativeResponse) {
  editingCreative.value = creative
  form.value = {
    title: creative.title,
    imageUrl: creative.imageUrl ?? '',
    destinationUrl: creative.destinationUrl,
  }
  showDialog.value = true
}

async function handleSubmit() {
  submitting.value = true
  try {
    if (editingCreative.value) {
      // 更新
      const body: UpdateAdCreativeRequest = {
        title: form.value.title || undefined,
        imageUrl: form.value.imageUrl || undefined,
        destinationUrl: form.value.destinationUrl || undefined,
      }
      const res = await advertiserApi.updateCreative('ORGANIZATION', orgSlug, campaignId, editingCreative.value.id, body)
      const idx = creatives.value.findIndex(c => c.id === editingCreative.value!.id)
      if (idx !== -1) {
        creatives.value[idx] = res.data
      }
      toast.add({ severity: 'success', summary: t('advertising.creative.updated_toast'), life: 3000 })
    }
    else {
      // 作成
      const body: CreateAdCreativeRequest = {
        title: form.value.title,
        imageUrl: form.value.imageUrl || undefined,
        destinationUrl: form.value.destinationUrl,
      }
      const res = await advertiserApi.createCreative('ORGANIZATION', orgSlug, campaignId, body)
      creatives.value.unshift(res.data)
      toast.add({ severity: 'success', summary: t('advertising.creative.created_toast'), life: 3000 })
    }
    showDialog.value = false
  }
  catch {
    toast.add({ severity: 'error', summary: t('advertising.creative.error_save'), life: 3000 })
  }
  finally {
    submitting.value = false
  }
}

function confirmDelete(creative: AdCreativeResponse) {
  confirm.require({
    message: t('advertising.creative.delete_confirm'),
    header: t('advertising.actions.delete'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    accept: () => handleDelete(creative),
  })
}

async function handleDelete(creative: AdCreativeResponse) {
  try {
    await advertiserApi.deleteCreative('ORGANIZATION', orgSlug, campaignId, creative.id)
    const idx = creatives.value.findIndex(c => c.id === creative.id)
    if (idx !== -1) {
      const current = creatives.value[idx]!
      creatives.value[idx] = { ...current, status: 'ENDED' } as AdCreativeResponse
    }
    toast.add({ severity: 'success', summary: t('advertising.creative.deleted_toast'), life: 3000 })
  }
  catch {
    toast.add({ severity: 'error', summary: t('advertising.creative.error_delete'), life: 3000 })
  }
}

onMounted(load)
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader :title="t('advertising.creative.title')" :back-to="`/organizations/${orgSlug}/advertiser`" />
        <p class="text-sm text-surface-500">
          {{ t('advertising.creative.description') }}
        </p>
      </div>
      <Button
        icon="pi pi-plus"
        :label="t('advertising.creative.create_button')"
        @click="openCreateDialog"
      />
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-20"><LoadingBounce /></div>

    <!-- 空状態 -->
    <div v-else-if="creatives.length === 0" class="py-20 text-center">
      <i class="pi pi-image mb-4 text-6xl text-surface-300" />
      <p class="text-surface-500">
        {{ t('advertising.creative.empty') }}
      </p>
      <Button
        class="mt-4"
        icon="pi pi-plus"
        :label="t('advertising.creative.create_button')"
        @click="openCreateDialog"
      />
    </div>

    <!-- クリエイティブ一覧テーブル -->
    <DataTable v-else :value="creatives" :row-hover="true" class="w-full">
      <Column field="title" :header="t('advertising.creative.column_title')" />
      <Column :header="t('advertising.creative.column_status')">
        <template #body="{ data }">
          <Tag
            :value="t(`advertising.creative.status.${data.status}`)"
            :severity="statusSeverityMap[data.status] ?? 'secondary'"
          />
        </template>
      </Column>
      <Column :header="t('advertising.creative.column_destination')">
        <template #body="{ data }">
          <a
            :href="data.destinationUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="text-primary underline"
          >
            {{ data.destinationUrl.length > 40 ? data.destinationUrl.slice(0, 40) + '…' : data.destinationUrl }}
          </a>
        </template>
      </Column>
      <Column :header="t('advertising.creative.column_created_at')">
        <template #body="{ data }">
          {{ formatDate(data.createdAt) }}
        </template>
      </Column>
      <Column :header="t('advertising.creative.column_actions')">
        <template #body="{ data }">
          <div class="flex gap-2">
            <Button
              v-if="data.status !== 'ENDED'"
              icon="pi pi-pencil"
              size="small"
              severity="secondary"
              text
              @click="openEditDialog(data)"
            />
            <Button
              v-if="data.status !== 'ENDED'"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              @click="confirmDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingCreative ? t('advertising.creative.dialog_edit_title') : t('advertising.creative.dialog_create_title')"
      modal
      class="w-full max-w-lg"
    >
      <div class="flex flex-col gap-4">
        <!-- タイトル -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('advertising.creative.form.title_label') }}</label>
          <InputText
            v-model="form.title"
            :placeholder="t('advertising.creative.form.title_placeholder')"
            :maxlength="200"
          />
        </div>

        <!-- 画像URL -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('advertising.creative.form.image_url_label') }}</label>
          <InputText
            v-model="form.imageUrl"
            :placeholder="t('advertising.creative.form.image_url_placeholder')"
            :maxlength="500"
          />
        </div>

        <!-- リンク先URL -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">{{ t('advertising.creative.form.destination_url_label') }}</label>
          <InputText
            v-model="form.destinationUrl"
            :placeholder="t('advertising.creative.form.destination_url_placeholder')"
            :maxlength="500"
          />
        </div>
      </div>

      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          text
          @click="showDialog = false"
        />
        <Button
          :label="$t('button.save')"
          icon="pi pi-check"
          :loading="submitting"
          :disabled="!form.title || !form.destinationUrl"
          @click="handleSubmit"
        />
      </template>
    </Dialog>
  </div>
</template>
