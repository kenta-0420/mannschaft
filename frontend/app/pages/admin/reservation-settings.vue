<script setup lang="ts">
import type { ReservationLineResponse } from '~/types/reservation'

definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? 0)
const scopeType = computed((): 'TEAM' | 'ORGANIZATION' =>
  scopeStore.current.type === 'organization' ? 'ORGANIZATION' : 'TEAM',
)
const { success, error: showError } = useNotification()
const { getLines, createLine, updateLine, deleteLine } = useReservationApi()

interface ReservationLineForm {
  name: string
  description: string
  displayOrder: string
  isActive: boolean
}

const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingItem = ref<ReservationLineResponse | null>(null)
const form = ref<ReservationLineForm>({ name: '', description: '', displayOrder: '1', isActive: true })
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getLines(scopeId.value)
    lines.value = (res as { data: ReservationLineResponse[] }).data
  } catch {
    showError('予約ラインの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { name: '', description: '', displayOrder: '1', isActive: true }
  showDialog.value = true
}

function openEdit(item: ReservationLineResponse) {
  editingItem.value = item
  form.value = {
    name: item.meta?.name ?? '',
    description: item.meta?.description ?? '',
    displayOrder: String(item.meta?.displayOrder ?? 1),
    isActive: item.meta?.isActive ?? true,
  }
  showDialog.value = true
}

async function save() {
  if (!form.value.name) return
  saving.value = true
  try {
    const body = {
      name: form.value.name,
      description: form.value.description || undefined,
      displayOrder: Number(form.value.displayOrder) || 1,
      isActive: form.value.isActive,
    }
    if (editingItem.value) {
      await updateLine(scopeId.value, editingItem.value.id ?? 0, body)
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

async function remove(item: ReservationLineResponse) {
  if (!confirm(`「${item.meta?.name}」を削除しますか？`)) return
  try {
    await deleteLine(scopeId.value, item.id ?? 0)
    success('予約ラインを削除しました')
    await load()
  } catch {
    showError('削除に失敗しました')
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })

const historyRef = ref<{ refresh: () => void } | null>(null)
function onNotificationSent() {
  historyRef.value?.refresh()
}
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader title="予約管理設定"><p class="text-sm text-surface-500">予約ライン（スタッフ・窓口）を管理します</p></PageHeader>
      </div>
      <Button label="ラインを追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="lines" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" message="予約ラインがありません" />
      </template>
      <Column header="ライン名">
        <template #body="{ data }">{{ data.meta?.name }}</template>
      </Column>
      <Column header="説明">
        <template #body="{ data }">{{ data.meta?.description }}</template>
      </Column>
      <Column header="表示順" style="width: 100px">
        <template #body="{ data }">{{ data.meta?.displayOrder }}</template>
      </Column>
      <Column header="状態" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="data.meta?.isActive ? '有効' : '無効'" :severity="data.meta?.isActive ? 'success' : 'secondary'" />
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

    <!-- 確認通知送信セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.send') }}</h2>
      <ConfirmableNotificationSender
        :scope-type="scopeType"
        :scope-id="scopeId"
        @sent="onNotificationSent"
      />
    </section>

    <!-- 発信履歴セクション -->
    <section class="mt-8">
      <h2 class="text-lg font-semibold mb-4">{{ $t('confirmable.history') }}</h2>
      <ConfirmableNotificationHistory
        ref="historyRef"
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
        <div>
          <label class="mb-1 block text-sm font-medium">説明</label>
          <InputText v-model="form.description" class="w-full" placeholder="例: 受付窓口" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">表示順</label>
          <InputText v-model="form.displayOrder" type="number" class="w-full" />
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
