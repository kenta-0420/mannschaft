<script setup lang="ts">
import type { ReservationLineResponse } from '~/types/reservation'

const props = defineProps<{
  teamId: string
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const confirm = useConfirm()
// 多重防御（defense-in-depth）: 親タブの v-if に加え、破壊的操作ボタンを本コンポーネントでも
// ロールで制御する。BE が本防御線だが、別画面から再利用された際の誤表示を防ぐ。
const { isAdmin, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingLine = ref<ReservationLineResponse | null>(null)
const form = ref({ name: '', description: '' })

async function loadLines() {
  loading.value = true
  try {
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data as ReservationLineResponse[]
  }
  catch { lines.value = [] }
  finally { loading.value = false }
}

function openCreate() {
  editingLine.value = null
  form.value = { name: '', description: '' }
  showDialog.value = true
}

function openEdit(line: ReservationLineResponse) {
  editingLine.value = line
  form.value = { name: line.meta?.name ?? '', description: line.meta?.description ?? '' }
  showDialog.value = true
}

async function save() {
  if (!form.value.name.trim()) return
  try {
    if (editingLine.value) {
      await reservationApi.updateLine(props.teamId, editingLine.value.id ?? 0, form.value)
      notification.success(t('reservation.message.line_update_success'))
    } else {
      await reservationApi.createLine(props.teamId, form.value)
      notification.success(t('reservation.message.line_create_success'))
    }
    showDialog.value = false
    await loadLines()
  }
  catch { notification.error(t('reservation.message.line_save_failed')) }
}

// 削除確認は既存の ReservationList.cancel() と同一パターン（PrimeVue ConfirmDialog）に合わせる。
// ネイティブ confirm() は改行やアイコンを表現できず、新仕様（テンプレ停止＋未来枠 purge）の
// 注意文言を十分に伝えられないため置き換える。
function remove(lineId: number) {
  confirm.require({
    // ガイド文言（新仕様の説明）を先に伝え、確認の問いかけを最後に置く（settings.delete_account 系と同じ順序規約）。
    message: `${t('reservation.line.delete_guide')} ${t('reservation.dialog.line_delete_confirm')}`,
    header: t('reservation.dialog.title'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('reservation.button.delete_line'),
    rejectLabel: t('reservation.button.cancel'),
    acceptClass: 'p-button-danger',
    accept: async () => {
      await reservationApi.deleteLine(props.teamId, lineId)
      notification.success(t('reservation.message.line_delete_success'))
      await loadLines()
    },
  })
}

onMounted(async () => {
  await loadPermissions()
  await loadLines()
})
</script>

<template>
  <div>
    <ConfirmDialog />
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">{{ t('reservation.line_manage_title') }}</h3>
      <Button v-if="isAdmin" :label="t('reservation.button.add_line')" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="lines.length > 0" class="space-y-2">
      <div v-for="line in lines" :key="line.id" class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600">
        <div class="min-w-0 flex-1">
          <p class="font-medium">{{ line.meta?.name }}</p>
          <p class="text-xs text-surface-500">{{ line.meta?.isActive ? t('reservation.state.active') : t('reservation.state.inactive') }}</p>
        </div>
        <Button v-if="isAdmin" icon="pi pi-pencil" text rounded size="small" @click="openEdit(line)" />
        <Button v-if="isAdmin" icon="pi pi-trash" text rounded size="small" severity="danger" @click="remove(line.id ?? 0)" />
      </div>
    </div>
    <DashboardEmptyState v-else icon="pi pi-list" :message="t('reservation.empty.no_lines_yet')" />

    <Dialog v-model:visible="showDialog" :header="editingLine ? t('reservation.dialog.line_edit') : t('reservation.dialog.line_create')" :style="{ width: '400px' }" modal>
      <div class="flex flex-col gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.name') }}</label>
          <InputText v-model="form.name" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.description') }}</label>
          <InputText v-model="form.description" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('reservation.button.cancel')" text @click="showDialog = false" />
        <Button :label="t('reservation.button.save')" icon="pi pi-check" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
