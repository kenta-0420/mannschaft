<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { getEquipmentList, createEquipment, updateEquipment, deleteEquipment } = useEquipmentApi()

interface EquipmentItem {
  id: number
  name: string
  categoryId: number | null
  status: string
  quantity: number
  availableQuantity: number
  description: string | null
  imageUrl: string | null
}

const items = ref<EquipmentItem[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<EquipmentItem | null>(null)
const form = ref({ name: '', quantity: '1', description: '' })
const saving = ref(false)

function statusLabel(s: string) {
  const map: Record<string, string> = {
    AVAILABLE: '利用可能', IN_USE: '使用中', MAINTENANCE: 'メンテナンス', LOST: '紛失',
  }
  return map[s] ?? s
}

function statusSeverity(s: string): string {
  const map: Record<string, string> = {
    AVAILABLE: 'success', IN_USE: 'info', MAINTENANCE: 'warning', LOST: 'danger',
  }
  return map[s] ?? 'secondary'
}

async function load() {
  loading.value = true
  try {
    const res = await getEquipmentList(scopeType.value as 'team' | 'organization', scopeId.value)
    items.value = (res as unknown as { data: EquipmentItem[] }).data
  } catch {
    showError('備品一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { name: '', quantity: '1', description: '' }
  showDialog.value = true
}

function openEdit(item: EquipmentItem) {
  editingItem.value = item
  form.value = { name: item.name, quantity: String(item.quantity), description: item.description ?? '' }
  showDialog.value = true
}

async function save() {
  if (!form.value.name) return
  saving.value = true
  try {
    const body = {
      name: form.value.name,
      quantity: Number(form.value.quantity) || 1,
      description: form.value.description || null,
      scopeType: scopeType.value,
      scopeId: scopeId.value,
    }
    if (editingItem.value) {
      await updateEquipment(scopeType.value as 'team' | 'organization', scopeId.value, editingItem.value.id, body)
      success('備品を更新しました')
    } else {
      await createEquipment(scopeType.value as 'team' | 'organization', scopeId.value, body)
      success('備品を追加しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function remove(item: EquipmentItem) {
  if (!confirm(`「${item.name}」を削除しますか？`)) return
  try {
    await deleteEquipment(scopeType.value as 'team' | 'organization', scopeId.value, item.id)
    success('備品を削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <PageHeader title="備品管理" />
      <Button label="備品を追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="items" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-box" message="備品がありません" />
      </template>
      <Column field="name" header="備品名" />
      <Column header="ステータス" style="width: 130px">
        <template #body="{ data }">
          <Tag :value="statusLabel(data.status)" :severity="statusSeverity(data.status)" />
        </template>
      </Column>
      <Column header="数量" style="width: 100px">
        <template #body="{ data }">
          <span class="text-sm">{{ data.availableQuantity }} / {{ data.quantity }}</span>
        </template>
      </Column>
      <Column field="description" header="説明">
        <template #body="{ data }">
          <span class="text-sm text-surface-500">{{ data.description ?? '-' }}</span>
        </template>
      </Column>
      <Column header="操作" style="width: 100px">
        <template #body="{ data }">
          <div class="flex gap-1">
            <Button icon="pi pi-pencil" size="small" text severity="info" @click="openEdit(data)" />
            <Button icon="pi pi-trash" size="small" text severity="danger" @click="remove(data)" />
          </div>
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? '備品編集' : '備品追加'"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">備品名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" class="w-full" placeholder="例: バスケットボール" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">数量</label>
          <InputText v-model="form.quantity" type="number" class="w-full" placeholder="1" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <Textarea v-model="form.description" class="w-full" rows="2" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button label="保存" :loading="saving" :disabled="!form.name" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
