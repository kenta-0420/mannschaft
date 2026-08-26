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

/** 呼称の動的差し込み（F03.4.5 §5.2）: 見出し・追加ボタンに使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const showDialog = ref(false)
const editingLine = ref<ReservationLineResponse | null>(null)
const form = ref({ name: '', description: '' })
/** 並び順編集（F03.4.5 §5.4）: 上下移動ボタンの二重クリック防止。 */
const reordering = ref(false)

async function loadLines() {
  loading.value = true
  try {
    // BE は displayOrder 昇順で返す（findByTeamIdOrderByDisplayOrderAsc・実測）。
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data as ReservationLineResponse[]
  }
  catch { lines.value = [] }
  finally { loading.value = false }
}

/**
 * 並び順編集（F03.4.5 §5.4・死蔵キー field/column.display_order の再利用）。
 * 隣接する2件の displayOrder を入れ替えて PATCH し、一覧を再読込する（BE 側は displayOrder 昇順ソートのため
 * 値を入れ替えるだけで表示順が反映される）。
 */
async function move(index: number, direction: -1 | 1) {
  const targetIndex = index + direction
  if (targetIndex < 0 || targetIndex >= lines.value.length || reordering.value) return
  const current = lines.value[index]
  const target = lines.value[targetIndex]
  if (!current?.id || !target?.id) return
  const currentOrder = current.meta?.displayOrder ?? index + 1
  const targetOrder = target.meta?.displayOrder ?? targetIndex + 1
  reordering.value = true
  try {
    await Promise.all([
      reservationApi.updateLine(props.teamId, current.id, { displayOrder: targetOrder }),
      reservationApi.updateLine(props.teamId, target.id, { displayOrder: currentOrder }),
    ])
    await loadLines()
  }
  catch { notification.error(t('reservation.message.line_save_failed')) }
  finally { reordering.value = false }
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
  await loadResourceName()
})

/**
 * 親（TeamReservationsPanel）のアコーディオン件数バッジ用（既存 FriendFolderList 等と同一パターン）。
 * refresh は呼称設定変更後の再読込（onResourceNameChanged）からも呼ばれるため、一覧に加えて
 * 呼称表示も合わせて最新化する。
 */
async function refresh() {
  await Promise.all([loadLines(), loadResourceName()])
}

defineExpose({ refresh, items: lines })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">{{ t('reservation.line_manage_title', { resourceName }) }}</h3>
      <Button v-if="isAdmin" :label="t('reservation.button.add_line', { resourceName })" icon="pi pi-plus" size="small" @click="openCreate" />
    </div>
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="lines.length > 0" class="space-y-2">
      <div v-for="(line, index) in lines" :key="line.id" class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600">
        <!-- 並び順編集（F03.4.5 §5.4）: ADMIN限定の上下移動ボタン -->
        <div v-if="isAdmin" class="flex flex-col">
          <Button
            icon="pi pi-chevron-up"
            text
            rounded
            size="small"
            :disabled="index === 0 || reordering"
            :aria-label="t('reservation.field.display_order')"
            data-testid="line-move-up"
            @click="move(index, -1)"
          />
          <Button
            icon="pi pi-chevron-down"
            text
            rounded
            size="small"
            :disabled="index === lines.length - 1 || reordering"
            :aria-label="t('reservation.field.display_order')"
            data-testid="line-move-down"
            @click="move(index, 1)"
          />
        </div>
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
