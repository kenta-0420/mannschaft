<script setup lang="ts">
import type { ReservationLineResponse } from '~/types/reservation'

definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? '')
const scopeType = computed((): 'TEAM' | 'ORGANIZATION' =>
  scopeStore.current.type === 'organization' ? 'ORGANIZATION' : 'TEAM',
)
const { t } = useI18n()
const { success, error: showError } = useNotification()
const { getLines, createLine, updateLine, deleteLine } = useReservationApi()

/**
 * 呼称の動的差し込み（F03.4.5 §5.2）。本ページは現状維持（構造は触らない）だが、
 * ボタン・ダイアログ見出しの「予約対象」ラベルのみ動的化する（殿の指示）。
 * ORGANIZATION スコープでは reservation-settings（TEAM専用API）取得が失敗し DEFAULT にフォールバックする
 * （useResourceName の既定挙動・従来表示「予約対象」と完全一致するため回帰なし）。
 */
const { resourceName, load: loadResourceName } = useResourceName(scopeId)

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
    showError(t('reservation.message.line_load_failed'))
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
      success(t('reservation.message.line_update_success_long'))
    } else {
      await createLine(scopeId.value, body)
      success(t('reservation.message.line_create_success_long'))
    }
    showDialog.value = false
    await load()
  } catch {
    showError(t('reservation.message.save_failed'))
  } finally {
    saving.value = false
  }
}

async function remove(item: ReservationLineResponse) {
  if (!confirm(t('reservation.dialog.line_delete_named_confirm', { name: item.meta?.name ?? '' }))) return
  try {
    await deleteLine(scopeId.value, item.id ?? 0)
    success(t('reservation.message.line_delete_success_long'))
    await load()
  } catch {
    showError(t('reservation.message.delete_failed'))
  }
}

watch(scopeId, (v) => { if (v) { load(); void loadResourceName() } })
onMounted(() => { if (scopeId.value) { load(); void loadResourceName() } })

const historyRef = ref<{ refresh: () => void } | null>(null)
function onNotificationSent() {
  historyRef.value?.refresh()
}
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader :title="t('reservation.page.settings_title')"><p class="text-sm text-surface-500">{{ t('reservation.page.settings_subtitle') }}</p></PageHeader>
      </div>
      <Button :label="t('reservation.button.add_line_long', { resourceName })" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="lines" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-calendar" :message="t('reservation.empty.no_lines')" />
      </template>
      <Column :header="t('reservation.column.line_name')">
        <template #body="{ data }">{{ data.meta?.name }}</template>
      </Column>
      <Column :header="t('reservation.column.description')">
        <template #body="{ data }">{{ data.meta?.description }}</template>
      </Column>
      <Column :header="t('reservation.column.display_order')" style="width: 100px">
        <template #body="{ data }">{{ data.meta?.displayOrder }}</template>
      </Column>
      <Column :header="t('reservation.column.state')" style="width: 100px">
        <template #body="{ data }">
          <Tag :value="data.meta?.isActive ? t('reservation.state.active') : t('reservation.state.inactive')" :severity="data.meta?.isActive ? 'success' : 'secondary'" />
        </template>
      </Column>
      <Column :header="t('reservation.column.action')" style="width: 100px">
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
      :header="editingItem ? t('reservation.dialog.line_edit_long', { resourceName }) : t('reservation.dialog.line_create_long', { resourceName })"
      :style="{ width: '420px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.line_name_required') }} <span class="text-red-500">*</span></label>
          <InputText v-model="form.name" class="w-full" :placeholder="t('reservation.placeholder.line_name')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.description') }}</label>
          <InputText v-model="form.description" class="w-full" :placeholder="t('reservation.placeholder.line_description')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.display_order') }}</label>
          <InputText v-model="form.displayOrder" type="number" class="w-full" />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isActive" input-id="isActive" />
          <label for="isActive" class="text-sm">{{ t('reservation.field.active') }}</label>
        </div>
      </div>
      <template #footer>
        <Button :label="t('reservation.button.cancel')" severity="secondary" text @click="showDialog = false" />
        <Button :label="t('reservation.button.save')" :loading="saving" :disabled="!form.name" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
