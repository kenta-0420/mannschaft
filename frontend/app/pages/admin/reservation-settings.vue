<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? 0)
const scopeType = computed((): 'TEAM' | 'ORGANIZATION' =>
  scopeStore.current.type === 'organization' ? 'ORGANIZATION' : 'TEAM',
)
const { success, error: showError } = useNotification()
const { getLines, createLine, updateLine, deleteLine } = useReservationApi()

interface ReservationLine {
  id: number
  name: string
  capacity: number
  slotDurationMinutes: number
  isActive: boolean
}

const lines = ref<ReservationLine[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<ReservationLine | null>(null)
const form = ref({ name: '', capacity: '1', slotDurationMinutes: '60', isActive: true })
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getLines(scopeId.value)
    lines.value = (res as { data: ReservationLine[] }).data
  } catch {
    showError('予約ラインの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { name: '', capacity: '1', slotDurationMinutes: '60', isActive: true }
  showDialog.value = true
}

function openEdit(item: ReservationLine) {
  editingItem.value = item
  form.value = {
    name: item.name,
    capacity: String(item.capacity),
    slotDurationMinutes: String(item.slotDurationMinutes),
    isActive: item.isActive,
  }
  showDialog.value = true
}

async function save() {
  if (!form.value.name) return
  saving.value = true
  try {
    const body = {
      name: form.value.name,
      capacity: Number(form.value.capacity) || 1,
      slotDurationMinutes: Number(form.value.slotDurationMinutes) || 60,
      isActive: form.value.isActive,
    }
    if (editingItem.value) {
      await updateLine(scopeId.value, editingItem.value.id, body)
      success('予約ラインを更新しました')
    } else {
      await createLine(scopeId.value, body)
      success('予約ラインを作成しました')
    }
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

async function remove(item: ReservationLine) {
  if (!confirm(`「${item.name}」を削除しますか？`)) return
  try {
    await deleteLine(scopeId.value, item.id)
    success('予約ラインを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold">予約管理設定</h1>
        <p class="mt-1 text-sm text-surface-500">予約ライン（スタッフ・窓口）を管理します</p>
      </div>
      <Button label="ラインを追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="lines" striped-rows data-key="id">
      <template #empty>
        <div class="py-12 text-center">
          <i class="pi pi-calendar mb-3 text-4xl text-surface-300" />
          <p class="text-surface-400">予約ラインがありません</p>
        </div>
      </template>
      <Column field="name" header="ライン名" />
      <Column header="定員" style="width: 80px">
        <template #body="{ data }">{{ data.capacity }}人</template>
      </Column>
      <Column header="枠時間" style="width: 100px">
        <template #body="{ data }">{{ data.slotDurationMinutes }}分</template>
      </Column>
      <Column header="状態" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="data.isActive ? '有効' : '無効'" :severity="data.isActive ? 'success' : 'secondary'" />
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

    <!-- 確認通知設定セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.settings') }}</h2>
      <ConfirmableNotificationSettings
        :scope-type="scopeType"
        :scope-id="scopeId"
      />
    </section>

    <Dialog
      v-model:visible="showDialog"
      :header="editingItem ? '予約ライン編集' : '予約ライン追加'"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">ライン名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" class="w-full" placeholder="例: 担当A" />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">定員（人）</label>
            <InputText v-model="form.capacity" type="number" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">枠時間（分）</label>
            <InputText v-model="form.slotDurationMinutes" type="number" class="w-full" />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isActive" input-id="isActive" />
          <label for="isActive" class="text-sm">有効</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button label="保存" :loading="saving" :disabled="!form.name" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
