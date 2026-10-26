<script setup lang="ts">
import type { PackageResponse } from '~/types/package'
import type { ModuleResponse } from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const packageApi = usePackageApi()
const systemAdminApi = useSystemAdminApi()
const { success, error: showError } = useNotification()

const packages = ref<PackageResponse[]>([])
const allModules = ref<ModuleResponse[]>([])
const loading = ref(true)

// ダイアログ制御
const showFormDialog = ref(false)
const editingId = ref<number | null>(null)
const formSubmitting = ref(false)

const form = ref({
  name: '',
  description: '',
  moduleIds: [] as number[],
  price: 0,
  discountRate: 0,
})

const formTitle = computed(() => (editingId.value ? 'パッケージ編集' : 'パッケージ作成'))

async function load() {
  loading.value = true
  try {
    const [pkgRes, modRes] = await Promise.all([
      packageApi.getPackages(),
      systemAdminApi.getModules(),
    ])
    packages.value = pkgRes.data
    allModules.value = modRes.data
  } catch {
    showError('パッケージ一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.value = { name: '', description: '', moduleIds: [], price: 0, discountRate: 0 }
  showFormDialog.value = true
}

async function openEdit(pkg: PackageResponse) {
  editingId.value = pkg.id
  form.value = {
    name: pkg.name,
    description: pkg.description,
    moduleIds: pkg.modules.map((m) => m.moduleId),
    price: pkg.price,
    discountRate: pkg.discountRate,
  }
  showFormDialog.value = true
}

async function submitForm() {
  if (!form.value.name || form.value.moduleIds.length === 0) return
  formSubmitting.value = true
  try {
    if (editingId.value) {
      await packageApi.updatePackage(editingId.value, { ...form.value })
      success('パッケージを更新しました')
    } else {
      await packageApi.createPackage({
        name: form.value.name,
        description: form.value.description || undefined,
        moduleIds: form.value.moduleIds,
        price: form.value.price,
        discountRate: form.value.discountRate || undefined,
      })
      success('パッケージを作成しました')
    }
    showFormDialog.value = false
    load()
  } catch {
    showError(editingId.value ? '更新に失敗しました' : '作成に失敗しました')
  } finally {
    formSubmitting.value = false
  }
}

async function handleTogglePublish(pkg: PackageResponse) {
  try {
    await packageApi.togglePublish(pkg.id)
    success(pkg.isPublished ? '非公開にしました' : '公開しました')
    load()
  } catch {
    showError('公開状態の変更に失敗しました')
  }
}

async function handleDelete(pkg: PackageResponse) {
  try {
    await packageApi.deletePackage(pkg.id)
    success('パッケージを削除しました')
    load()
  } catch {
    showError('削除に失敗しました')
  }
}

function formatPrice(price: number): string {
  return price.toLocaleString('ja-JP')
}

const moduleOptions = computed(() =>
  allModules.value.map((m) => ({ label: m.name, value: m.id })),
)

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader title="パッケージ管理" />
      <Button label="新規作成" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="packages" striped-rows data-key="id">
      <template #empty>
        <div class="py-8 text-center text-surface-500">パッケージがありません</div>
      </template>

      <Column field="name" header="パッケージ名" />

      <Column header="含有モジュール">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Tag
              v-for="m in (data as PackageResponse).modules"
              :key="m.moduleId"
              :value="m.moduleName"
              severity="info"
            />
          </div>
        </template>
      </Column>

      <Column header="価格" style="width: 120px">
        <template #body="{ data }">
          <span class="font-medium">&yen;{{ formatPrice((data as PackageResponse).price) }}</span>
        </template>
      </Column>

      <Column header="割引率" style="width: 100px">
        <template #body="{ data }">
          <span>{{ (data as PackageResponse).discountRate }}%</span>
        </template>
      </Column>

      <Column header="公開状態" style="width: 100px">
        <template #body="{ data }">
          <Tag
            :value="(data as PackageResponse).isPublished ? '公開' : '非公開'"
            :severity="(data as PackageResponse).isPublished ? 'success' : 'secondary'"
          />
        </template>
      </Column>

      <Column header="操作" style="width: 240px">
        <template #body="{ data }">
          <div class="flex flex-wrap gap-1">
            <Button
              label="編集"
              size="small"
              severity="info"
              text
              @click="openEdit(data as PackageResponse)"
            />
            <Button
              :label="(data as PackageResponse).isPublished ? '非公開' : '公開'"
              size="small"
              :severity="(data as PackageResponse).isPublished ? 'warn' : 'success'"
              outlined
              @click="handleTogglePublish(data as PackageResponse)"
            />
            <Button
              v-tooltip="'削除'"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              @click="handleDelete(data as PackageResponse)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showFormDialog"
      :header="formTitle"
      :style="{ width: '560px' }"
      modal
      :draggable="false"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">
            パッケージ名 <span class="text-red-500">*</span>
          </label>
          <InputText v-model="form.name" class="w-full" placeholder="例: スタンダードプラン" />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea
            v-model="form.description"
            class="w-full"
            rows="3"
            placeholder="パッケージの説明を入力"
          />
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">
            モジュール選択 <span class="text-red-500">*</span>
          </label>
          <MultiSelect
            v-model="form.moduleIds"
            :options="moduleOptions"
            option-label="label"
            option-value="value"
            placeholder="モジュールを選択"
            class="w-full"
            display="chip"
          />
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">価格 (円)</label>
            <InputNumber v-model="form.price" class="w-full" :min="0" placeholder="0" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">割引率 (%)</label>
            <InputNumber
              v-model="form.discountRate"
              class="w-full"
              :min="0"
              :max="100"
              placeholder="0"
            />
          </div>
        </div>
      </div>

      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showFormDialog = false" />
        <Button
          :label="editingId ? '更新する' : '作成する'"
          icon="pi pi-check"
          :loading="formSubmitting"
          :disabled="!form.name || form.moduleIds.length === 0"
          @click="submitForm"
        />
      </template>
    </Dialog>
  </div>
</template>
