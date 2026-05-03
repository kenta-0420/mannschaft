<script setup lang="ts">
import type { TaxSettingResponse, CreateTaxSettingRequest, UpdateTaxSettingRequest } from '~/types/tax-setting'

definePageMeta({ middleware: 'auth' })

const { getTaxSettings, createTaxSetting, updateTaxSetting, deleteTaxSetting } = useTaxSettingApi()
const { success, error: showError } = useNotification()

const taxSettings = ref<TaxSettingResponse[]>([])
const loading = ref(true)

// ダイアログ制御
const showDialog = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const confirmDelete = ref(false)
const deleteTarget = ref<TaxSettingResponse | null>(null)

const emptyForm = (): CreateTaxSettingRequest => ({
  name: '',
  rate: 10,
  isIncludedInPrice: true,
  isActive: true,
})
const form = ref<CreateTaxSettingRequest>(emptyForm())

const dialogHeader = computed(() => editingId.value ? '税率を編集' : '税率を追加')

async function load() {
  loading.value = true
  try {
    const res = await getTaxSettings()
    taxSettings.value = res.data
  }
  catch { taxSettings.value = [] }
  finally { loading.value = false }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showDialog.value = true
}

function openEdit(item: TaxSettingResponse) {
  editingId.value = item.id
  form.value = {
    name: item.name,
    rate: item.rate,
    isIncludedInPrice: item.isIncludedInPrice,
    isActive: item.isActive,
  }
  showDialog.value = true
}

async function submit() {
  if (!form.value.name) return
  submitting.value = true
  try {
    if (editingId.value) {
      const body: UpdateTaxSettingRequest = { ...form.value }
      await updateTaxSetting(editingId.value, body)
      success('税率を更新しました')
    }
    else {
      await createTaxSetting(form.value)
      success('税率を追加しました')
    }
    showDialog.value = false
    await load()
  }
  catch {
    showError(editingId.value ? '更新に失敗しました' : '追加に失敗しました')
  }
  finally { submitting.value = false }
}

function openDelete(item: TaxSettingResponse) {
  deleteTarget.value = item
  confirmDelete.value = true
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteTaxSetting(deleteTarget.value.id)
    success('税率を削除しました')
    confirmDelete.value = false
    deleteTarget.value = null
    await load()
  }
  catch { showError('削除に失敗しました') }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader title="消費税設定" />
      <Button label="税率を追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable
      v-else
      :value="taxSettings"
      data-key="id"
      striped-rows
    >
      <template #empty>
        <DashboardEmptyState icon="pi pi-percentage" message="税率が登録されていません" />
      </template>

      <Column field="name" header="税名称" />

      <Column header="税率" style="width: 120px">
        <template #body="{ data }">
          {{ data.rate }}%
        </template>
      </Column>

      <Column header="表示方式" style="width: 140px">
        <template #body="{ data }">
          <Tag
            :value="data.isIncludedInPrice ? '税込' : '税抜'"
            :severity="data.isIncludedInPrice ? 'info' : 'warning'"
          />
        </template>
      </Column>

      <Column header="ステータス" style="width: 120px">
        <template #body="{ data }">
          <Tag
            :value="data.isActive ? '有効' : '無効'"
            :severity="data.isActive ? 'success' : 'secondary'"
          />
        </template>
      </Column>

      <Column header="更新日" style="width: 140px">
        <template #body="{ data }">
          <span class="text-sm">
            {{ new Date(data.updatedAt).toLocaleDateString('ja-JP') }}
          </span>
        </template>
      </Column>

      <Column header="操作" style="width: 140px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button
              v-tooltip="'編集'"
              icon="pi pi-pencil"
              size="small"
              severity="secondary"
              text
              @click="openEdit(data)"
            />
            <Button
              v-tooltip="'削除'"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              @click="openDelete(data)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 作成/編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="dialogHeader"
      :style="{ width: '480px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            税名称 <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.name"
            class="w-full"
            placeholder="例: 消費税"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">
            税率（%）<span class="text-red-500">*</span>
          </label>
          <InputNumber
            v-model="form.rate"
            :min="0"
            :max="100"
            :max-fraction-digits="2"
            suffix="%"
            class="w-full"
          />
        </div>
        <div class="flex items-center gap-3">
          <ToggleSwitch v-model="form.isIncludedInPrice" />
          <label class="text-sm font-medium">
            {{ form.isIncludedInPrice ? '税込表示' : '税抜表示' }}
          </label>
        </div>
        <div class="flex items-center gap-3">
          <ToggleSwitch v-model="form.isActive" />
          <label class="text-sm font-medium">
            {{ form.isActive ? '有効' : '無効' }}
          </label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button
          :label="editingId ? '更新する' : '追加する'"
          icon="pi pi-check"
          :loading="submitting"
          :disabled="!form.name"
          @click="submit"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="confirmDelete"
      header="税率の削除"
      :style="{ width: '400px' }"
      modal
      :draggable="false"
    >
      <p>
        「{{ deleteTarget?.name }}」（{{ deleteTarget?.rate }}%）を削除しますか？
      </p>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="confirmDelete = false" />
        <Button label="削除する" icon="pi pi-trash" severity="danger" @click="handleDelete" />
      </template>
    </Dialog>
  </div>
</template>
