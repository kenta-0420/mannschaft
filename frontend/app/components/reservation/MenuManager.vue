<script setup lang="ts">
/**
 * 予約メニュー管理（機能E・F03.4.1 §10）ADMIN限定
 *
 * - 所要時間（30分単位・30〜480分）を持つメニューマスタの CRUD。
 * - 提供可否: lineIds 空配列 = 全ての予約対象で提供（入力摩擦ゼロ既定）。
 *   「選んだ予約対象のみ」を選ぶと MultiSelect で対象を限定できる。
 * - 料金は表示のみ（決済されない）。編集時に空へ戻す場合は clearPrice: true を送る
 *   （null 据え置きと null 設定を区別する PATCH セマンティクス）。
 * - エラーは BE コードで判定して表示（握りつぶし禁止）:
 *     RESERVATION_033(400) 上限20件 / RESERVATION_034(400) 所要時間不正 /
 *     RESERVATION_035(400) lineIds 不正。
 *
 * 金型: LineManager.vue（CRUDダイアログ型）。最終ゲートは BE。
 */
import type { components } from '~/types/generated'

type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']

const props = defineProps<{
  teamId: string
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
// 多重防御（defense-in-depth）: 親タブの v-if に加え、破壊的操作ボタンを本コンポーネントでも
// ロールで制御する。BE が本防御線だが、別画面から再利用された際の誤表示を防ぐ。
const { isAdmin, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

/** 呼称の動的差し込み（F03.4.5 §5.2）: メニューの提供先ライン選択ラベルに使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

const menus = ref<ReservationMenuResponse[]>([])
const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const saving = ref(false)
const showDialog = ref(false)
const editingMenu = ref<ReservationMenuResponse | null>(null)

/** 提供範囲の選択（ALL=全ライン提供可（lineIds空配列）/ SELECTED=列挙） */
type LineScope = 'ALL' | 'SELECTED'

interface MenuForm {
  name: string
  durationMinutes: number
  /** null = 料金非表示 */
  price: number | null
  description: string
  lineScope: LineScope
  lineIds: number[]
  isActive: boolean
}

const form = ref<MenuForm>(defaultForm())

function defaultForm(): MenuForm {
  return {
    name: '',
    durationMinutes: 30,
    price: null,
    description: '',
    lineScope: 'ALL',
    lineIds: [],
    isActive: true,
  }
}

/** 所要時間の選択肢（30分単位・30〜480分） */
const durationOptions = computed(() =>
  Array.from({ length: 16 }, (_, i) => {
    const m = (i + 1) * 30
    return { label: t('reservation.menu.duration_minutes_label', { m }), value: m }
  }),
)

const lineScopeOptions = computed(() => [
  { label: t('reservation.menu.lines_all'), value: 'ALL' as LineScope },
  { label: t('reservation.menu.lines_selected'), value: 'SELECTED' as LineScope },
])

/** MultiSelect の予約対象選択肢（active ラインのみ） */
const lineOptions = computed(() =>
  lines.value
    .filter(l => l.meta?.isActive)
    .map(l => ({ label: l.meta?.name ?? '', value: l.id ?? 0 })),
)

/** ライン名の解決（一覧のバッジ表示用） */
function lineName(lineId: number): string {
  return lines.value.find(l => l.id === lineId)?.meta?.name ?? String(lineId)
}

const saveDisabled = computed(() =>
  saving.value
  || !form.value.name.trim()
  || (form.value.lineScope === 'SELECTED' && form.value.lineIds.length === 0),
)

async function loadMenus() {
  loading.value = true
  try {
    const res = await reservationApi.getMenus(props.teamId)
    menus.value = res.data ?? []
  }
  catch {
    menus.value = []
    notification.error(t('reservation.message.menu_load_failed'))
  }
  finally {
    loading.value = false
  }
}

async function loadLines() {
  try {
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data ?? []
  }
  catch {
    lines.value = []
  }
}

function openCreate() {
  editingMenu.value = null
  form.value = defaultForm()
  showDialog.value = true
}

function openEdit(menu: ReservationMenuResponse) {
  editingMenu.value = menu
  form.value = {
    name: menu.name ?? '',
    durationMinutes: menu.durationMinutes ?? 30,
    price: menu.price ?? null,
    description: menu.description ?? '',
    lineScope: (menu.lineIds ?? []).length > 0 ? 'SELECTED' : 'ALL',
    lineIds: [...(menu.lineIds ?? [])],
    isActive: menu.isActive ?? true,
  }
  showDialog.value = true
}

/** BE エラーコード → 利用者向け文言（握りつぶさない） */
function notifySaveError(err: unknown) {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  switch (code) {
    case 'RESERVATION_033':
      notification.error(t('dialog.error'), t('reservation.menu.limit_reached'))
      return
    case 'RESERVATION_034':
      notification.error(t('dialog.error'), t('reservation.menu.duration_invalid'))
      return
    case 'RESERVATION_035':
      notification.error(t('dialog.error'), t('reservation.menu.line_ids_invalid'))
      return
    default:
      handleApiError(err)
  }
}

async function save() {
  if (saveDisabled.value) return
  saving.value = true
  // 提供範囲: ALL = 空配列（全ライン提供可）/ SELECTED = 列挙
  const lineIds = form.value.lineScope === 'ALL' ? [] : form.value.lineIds
  try {
    if (editingMenu.value?.id) {
      // PATCH: price を空へ戻す場合は clearPrice で明示（null 据え置きと区別）
      await reservationApi.updateMenu(props.teamId, editingMenu.value.id, {
        name: form.value.name.trim(),
        durationMinutes: form.value.durationMinutes,
        ...(form.value.price != null ? { price: form.value.price } : { clearPrice: true }),
        description: form.value.description.trim() || undefined,
        isActive: form.value.isActive,
        lineIds,
      })
      notification.success(t('reservation.message.menu_update_success'))
    }
    else {
      await reservationApi.createMenu(props.teamId, {
        name: form.value.name.trim(),
        durationMinutes: form.value.durationMinutes,
        price: form.value.price ?? undefined,
        description: form.value.description.trim() || undefined,
        lineIds,
      })
      notification.success(t('reservation.message.menu_create_success'))
    }
    showDialog.value = false
    await loadMenus()
  }
  catch (err) {
    notifySaveError(err)
  }
  finally {
    saving.value = false
  }
}

async function remove(menu: ReservationMenuResponse) {
  if (!menu.id) return
  if (!confirm(t('reservation.menu.delete_confirm'))) return
  try {
    await reservationApi.deleteMenu(props.teamId, menu.id)
    notification.success(t('reservation.message.menu_delete_success'))
    await loadMenus()
  }
  catch (err) {
    handleApiError(err)
  }
}

onMounted(async () => {
  await loadPermissions()
  await Promise.all([loadMenus(), loadLines(), loadResourceName()])
})

// 親（TeamReservationsPanel）のアコーディオン件数バッジ用（既存 FriendFolderList 等と同一パターン）。
defineExpose({ refresh: loadMenus, items: menus })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-lg font-semibold">{{ t('reservation.menu.title') }}</h3>
      <Button
        v-if="isAdmin"
        :label="t('reservation.menu.add')"
        icon="pi pi-plus"
        size="small"
        data-testid="menu-add"
        @click="openCreate"
      />
    </div>

    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="menus.length > 0" class="space-y-2">
      <div
        v-for="menu in menus"
        :key="menu.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
        :class="menu.isActive === false ? 'opacity-60' : ''"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ menu.name }}
            <span class="ml-2 text-sm text-surface-500">
              {{ t('reservation.menu.duration_minutes_label', { m: menu.durationMinutes }) }}
            </span>
            <span v-if="menu.price != null" class="ml-2 text-sm text-surface-500">
              ¥{{ menu.price.toLocaleString() }}
            </span>
          </p>
          <div class="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-surface-500">
            <span>{{ t('reservation.menu.required_slots', { n: menu.requiredSlotCount }) }}</span>
            <span>
              {{ (menu.lineIds ?? []).length === 0
                ? t('reservation.menu.lines_all')
                : (menu.lineIds ?? []).map(lineName).join(' / ') }}
            </span>
            <span v-if="menu.isActive === false">{{ t('reservation.state.inactive') }}</span>
          </div>
          <p v-if="menu.description" class="mt-0.5 truncate text-xs text-surface-500">
            {{ menu.description }}
          </p>
        </div>
        <Button v-if="isAdmin" icon="pi pi-pencil" text rounded size="small" @click="openEdit(menu)" />
        <Button v-if="isAdmin" icon="pi pi-trash" text rounded size="small" severity="danger" @click="remove(menu)" />
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-book"
      :message="t('reservation.menu.empty_state')"
      :sub-message="isAdmin ? t('reservation.menu.empty_state_hint') : undefined"
    >
      <template v-if="isAdmin" #action>
        <Button
          :label="t('reservation.menu.add')"
          icon="pi pi-plus"
          size="small"
          @click="openCreate"
        />
      </template>
    </DashboardEmptyState>

    <Dialog
      v-model:visible="showDialog"
      :header="editingMenu ? t('reservation.menu.edit') : t('reservation.menu.add')"
      :style="{ width: '440px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <!-- 名前 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.menu.name') }} <span class="text-red-500">*</span>
          </label>
          <InputText v-model="form.name" maxlength="100" class="w-full" data-testid="menu-name" />
        </div>

        <!-- 所要時間（30分単位 Select） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.menu.duration') }} <span class="text-red-500">*</span>
          </label>
          <Select
            v-model="form.durationMinutes"
            :options="durationOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 料金（任意・表示のみ） -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.menu.price') }}</label>
          <InputNumber v-model="form.price" :min="0" :max-fraction-digits="2" class="w-full" />
          <p class="mt-1 text-xs text-surface-500">{{ t('reservation.menu.price_display_only') }}</p>
        </div>

        <!-- 説明（任意） -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.menu.description') }}</label>
          <InputText v-model="form.description" maxlength="500" class="w-full" />
        </div>

        <!-- 提供可否（全ライン / 選択ライン） -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.line', { resourceName }) }}</label>
          <SelectButton
            v-model="form.lineScope"
            :options="lineScopeOptions"
            option-label="label"
            option-value="value"
            :allow-empty="false"
          />
          <MultiSelect
            v-if="form.lineScope === 'SELECTED'"
            v-model="form.lineIds"
            :options="lineOptions"
            option-label="label"
            option-value="value"
            display="chip"
            class="mt-2 w-full"
            :placeholder="t('reservation.placeholder.select')"
          />
          <p class="mt-1 text-xs text-surface-500">{{ t('reservation.menu.line_scope_note') }}</p>
        </div>

        <!-- 有効/無効（編集時のみ） -->
        <div v-if="editingMenu" class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ t('reservation.menu.is_active') }}</label>
          <ToggleSwitch v-model="form.isActive" />
        </div>
      </div>

      <template #footer>
        <Button :label="t('reservation.button.cancel')" text @click="showDialog = false" />
        <Button
          :label="t('reservation.button.save')"
          icon="pi pi-check"
          :loading="saving"
          :disabled="saveDisabled"
          data-testid="menu-save"
          @click="save"
        />
      </template>
    </Dialog>
  </div>
</template>
