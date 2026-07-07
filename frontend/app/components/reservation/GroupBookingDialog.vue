<script setup lang="ts">
/**
 * F03.4.4 §5.3「予約フロー（セルクリック → 確定）」のダイアログ本体。
 *
 * SlotMatrixPicker がセル（span=1・AVAILABLE）クリックで開く。
 * ステップ1: メニュー選択（menuId 事前絞り込み済みならスキップして即プレビュー）
 * ステップ2: 連続枠プレビュー（＋30分延長・共通枠列はライン選択）
 * 確定: N>=2 or メニュー指定あり → POST /reservation-groups（F03.4.3）
 *       N=1 かつメニューなし → 既存 POST /reservations（単枠フロー完全互換）
 *
 * 長尺手動枠（colspan>1）のクリックは対象外（SlotMatrixPicker が @slot-selected を emit し、
 * 親の既存 ReservationForm ダイアログへ回す。写経元 SlotGridPicker と同一パターン）。
 */
import type { components } from '~/types/generated'
import {
  collectConsecutiveSlotIds,
  canExtend,
  formatMinutes,
  type RowSlot,
  type HeaderSlot,
} from '~/utils/reservationMatrix'

type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']

export interface LineOption { id: number; name: string }

/** ダイアログを開くセルの文脈。 */
export interface GroupBookingContext {
  date: string
  /** null = 共通枠列（ライン選択が必要）。 */
  columnLineId: number | null
  columnLineName: string | null
  /** クリックされた行（同一日付×予約対象）の整列済みセル配列。 */
  rowSlots: RowSlot[]
  /** クリックされたヘッダ列インデックス（連続確保の起点）。 */
  startIndex: number
  header: HeaderSlot[]
  /** メニューフィルターが有効な場合の事前絞り込みメニュー（menu選択ステップをスキップ）。 */
  preselectedMenuId?: string | null
  preselectedRequiredCellCount?: number | null
}

const props = defineProps<{
  visible: boolean
  teamId: string
  lines: LineOption[]
  menus: ReservationMenuResponse[]
  context: GroupBookingContext | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 予約（単枠 or グループ）確定成功。親はグリッド・一覧を再読込する。 */
  reserved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()

type Step = 'menu' | 'preview'
const step = ref<Step>('menu')
const selectedMenuId = ref<string | null>(null)
const requiredCellCount = ref(1)
const selectedIndices = ref<number[]>([])
const selectedLineId = ref<number | null>(null)
const userNote = ref('')
const submitting = ref(false)
/** メニューを選んだが起点から連続枠が取れなかった場合の警告（menuステップに留まる）。 */
const menuStepWarning = ref('')
/** ライン提供不可（043）を受けた場合、プレビューでライン選択に注意を促す。 */
const lineWarning = ref('')
/** インライン折りたたみガイド（`/手助けモーダル` 方式・既定 collapsed）。 */
const guideOpen = ref<string | null>(null)

const isCommon = computed(() => props.context?.columnLineId == null)

const menuOptions = computed(() =>
  (props.menus ?? []).filter(m => m.isActive !== false),
)

function selectableMenuName(menu: ReservationMenuResponse): string {
  return `${menu.name ?? ''}（${t('reservation.menu.duration_minutes_label', { m: menu.durationMinutes ?? 0 })}）`
}

/** 選択中メニューの提供ライン制約チェック（line-column の事前チェック。共通枠は確定時に選ぶため対象外）。 */
function isMenuOfferedOnColumn(menu: ReservationMenuResponse): boolean {
  if (isCommon.value) return true
  const lineIds = menu.lineIds ?? []
  if (lineIds.length === 0) return true
  return lineIds.includes(props.context?.columnLineId ?? -1)
}

function resetState() {
  step.value = 'menu'
  selectedMenuId.value = null
  requiredCellCount.value = 1
  selectedIndices.value = []
  selectedLineId.value = null
  userNote.value = ''
  menuStepWarning.value = ''
  lineWarning.value = ''
  submitting.value = false
  guideOpen.value = null
}

function close() {
  emit('update:visible', false)
}

watch(() => props.visible, (v) => {
  if (v) {
    resetState()
    // メニューフィルター事前絞り込み済みなら menu ステップをスキップして即プレビューへ
    if (props.context?.preselectedMenuId && props.context.preselectedRequiredCellCount) {
      const menu = menuOptions.value.find(m => m.id === props.context?.preselectedMenuId)
      if (menu) applyMenuSelection(menu, props.context.preselectedRequiredCellCount)
    }
  }
})

/** メニュー選択（null=メニューなし30分）を適用し、連続枠が取れればプレビューへ進む。 */
function applyMenuSelection(menu: ReservationMenuResponse | null, count: number) {
  if (!props.context) return
  if (menu && !isMenuOfferedOnColumn(menu)) {
    menuStepWarning.value = t('reservation.menu.line_not_available')
    return
  }
  const ids = collectConsecutiveSlotIds(props.context.rowSlots, props.context.startIndex, count)
  if (!ids) {
    menuStepWarning.value = t('reservation.matrix.cannot_start_here', { menu: menu?.name ?? t('reservation.matrix.no_menu_single') })
    return
  }
  menuStepWarning.value = ''
  selectedMenuId.value = menu?.id ?? null
  requiredCellCount.value = count
  selectedIndices.value = Array.from({ length: count }, (_, i) => props.context!.startIndex + i)
  selectedLineId.value = isCommon.value ? null : (props.context.columnLineId ?? null)
  step.value = 'preview'
}

function onSelectMenu(menu: ReservationMenuResponse) {
  applyMenuSelection(menu, menu.requiredSlotCount ?? Math.max(1, Math.round((menu.durationMinutes ?? 30) / 30)))
}

function onSelectNoMenu() {
  applyMenuSelection(null, 1)
}

const selectedSlotIds = computed<number[] | null>(() => {
  if (!props.context) return null
  return collectConsecutiveSlotIds(props.context.rowSlots, props.context.startIndex, selectedIndices.value.length)
})

const canExtendNow = computed(() => {
  if (!props.context) return false
  return canExtend(props.context.rowSlots, selectedIndices.value)
})

function extend() {
  if (!canExtendNow.value) return
  const last = Math.max(...selectedIndices.value)
  selectedIndices.value = [...selectedIndices.value, last + 1]
}

const previewTimeLabel = computed(() => {
  if (!props.context || selectedIndices.value.length === 0) return ''
  const header = props.context.header
  const startIdx = Math.min(...selectedIndices.value)
  const endIdx = Math.max(...selectedIndices.value)
  const startLabel = header[startIdx]?.label ?? ''
  const endMinutes = (header[endIdx]?.minutes ?? 0) + 30
  return `${startLabel} - ${formatMinutes(endMinutes)}`
})

const canConfirm = computed(() => {
  if (submitting.value) return false
  if (!selectedSlotIds.value || selectedSlotIds.value.length === 0) return false
  if (isCommon.value && selectedLineId.value == null) return false
  return true
})

/** BE エラー応答から RESERVATION_xxx コードを取り出す（useErrorHandler と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

async function confirm() {
  if (!props.context || !canConfirm.value || !selectedSlotIds.value) return
  const lineId = selectedLineId.value
  if (lineId == null) return
  submitting.value = true
  lineWarning.value = ''
  try {
    if (selectedSlotIds.value.length === 1 && selectedMenuId.value == null) {
      // N=1・メニューなし: 既存単枠フロー完全互換
      await reservationApi.createReservation(props.teamId, {
        reservationSlotId: selectedSlotIds.value[0]!,
        lineId,
        userNote: userNote.value.trim() || undefined,
      })
    }
    else {
      await reservationApi.createGroup(props.teamId, {
        menuId: selectedMenuId.value ?? undefined,
        lineId,
        slotIds: selectedSlotIds.value,
        userNote: userNote.value.trim() || undefined,
      })
    }
    notification.success(t('reservation.message.reserve_success'))
    emit('reserved')
    close()
  }
  catch (error) {
    const code = extractErrorCode(error)
    switch (code) {
      case 'RESERVATION_039':
        // 満席/CLOSED で確保失敗。全ロールバック済み。グリッド再取得＋選び直し。
        notification.error(t('reservation.group.conflict_retry'))
        emit('reserved')
        close()
        break
      case 'RESERVATION_038':
        notification.error(t('reservation.group.not_consecutive'))
        emit('reserved')
        close()
        break
      case 'RESERVATION_041':
        notification.error(t('reservation.group.size_exceeded'))
        break
      case 'RESERVATION_043':
        // 提供不可ライン。共通枠列のライン選択へ戻す（プレビューに留まる）。
        lineWarning.value = t('reservation.menu.line_not_available')
        break
      case 'RESERVATION_013':
        // 自分の予約済み枠を含む重複（DUPLICATE_RESERVATION）。選択し直しを促すためプレビューに留まる
        // （039/038/009 のような「状況が変わった」ケースと異なり、選択自体の見直しが必要なため）。
        notification.error(t('reservation.group.own_overlap'))
        break
      case 'RESERVATION_009':
        notification.error(t('reservation.matrix.slot_unavailable_conflict'))
        emit('reserved')
        close()
        break
      default:
        notification.error(t('reservation.message.reserve_failed'))
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="step === 'menu' ? t('reservation.matrix.select_menu_title') : t('reservation.matrix.preview_title')"
    :style="{ width: '440px' }"
    modal
    @update:visible="close"
  >
    <div v-if="context" class="space-y-4">
      <!-- 折りたたみ式インライン使い方ガイド（既定 collapsed・`/手助けモーダル` 方式） -->
      <Accordion v-model:value="guideOpen">
        <AccordionPanel value="guide">
          <AccordionHeader>
            <span class="text-xs font-medium text-surface-500">
              {{ t('reservation.matrix.help_toggle') }}
            </span>
          </AccordionHeader>
          <AccordionContent>
            <p class="text-xs text-surface-600 dark:text-surface-300">
              {{ t('reservation.matrix.help_body') }}
            </p>
          </AccordionContent>
        </AccordionPanel>
      </Accordion>

      <!-- ステップ1: メニュー選択 -->
      <div v-if="step === 'menu'" class="space-y-2">
        <Message v-if="menuStepWarning" severity="warn" :closable="false">{{ menuStepWarning }}</Message>
        <button
          v-for="menu in menuOptions"
          :key="menu.id"
          type="button"
          :data-testid="`group-menu-option-${menu.id}`"
          class="flex w-full items-center justify-between rounded-lg border border-surface-200 p-3 text-left text-sm hover:border-primary hover:bg-primary/5 dark:border-surface-600"
          @click="onSelectMenu(menu)"
        >
          <span>{{ selectableMenuName(menu) }}</span>
          <i class="pi pi-angle-right text-surface-400" />
        </button>
        <button
          type="button"
          data-testid="group-no-menu"
          class="flex w-full items-center justify-between rounded-lg border border-dashed border-surface-300 p-3 text-left text-sm text-surface-600 hover:border-primary hover:bg-primary/5 dark:border-surface-600 dark:text-surface-300"
          @click="onSelectNoMenu"
        >
          <span>{{ t('reservation.matrix.no_menu_single') }}</span>
          <i class="pi pi-angle-right text-surface-400" />
        </button>
      </div>

      <!-- ステップ2: 連続枠プレビュー -->
      <div v-else class="space-y-4">
        <div class="rounded-lg bg-surface-50 p-4 dark:bg-surface-700/50">
          <div class="space-y-2 text-sm">
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('reservation.field.date') }}</span>
              <span class="font-medium">{{ context.date }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('reservation.group.time_range') }}</span>
              <span class="font-medium">{{ previewTimeLabel }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-surface-500">{{ t('reservation.group.title') }}</span>
              <span class="font-medium">{{ t('reservation.group.slot_count', { n: selectedIndices.length }) }}</span>
            </div>
          </div>
        </div>

        <div v-if="isCommon">
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.line') }}</label>
          <Select
            v-model="selectedLineId"
            data-testid="group-line-select"
            :options="lines"
            option-label="name"
            option-value="id"
            class="w-full"
            :placeholder="t('reservation.placeholder.select')"
          />
        </div>
        <div v-else class="text-sm">
          <span class="text-surface-500">{{ t('reservation.field.line') }}</span>
          <span class="ml-2 font-medium">{{ context.columnLineName }}</span>
        </div>

        <Message v-if="lineWarning" severity="warn" :closable="false">{{ lineWarning }}</Message>

        <Button
          data-testid="group-extend"
          :label="t('reservation.matrix.extend_30')"
          icon="pi pi-plus"
          text
          size="small"
          :disabled="!canExtendNow"
          :title="!canExtendNow ? t('reservation.matrix.cannot_extend') : undefined"
          @click="extend"
        />

        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.note') }}</label>
          <Textarea v-model="userNote" rows="2" class="w-full" :placeholder="t('reservation.placeholder.note')" />
        </div>
      </div>
    </div>

    <template #footer>
      <Button :label="t('reservation.button.cancel')" text @click="close" />
      <Button
        v-if="step === 'preview'"
        data-testid="group-confirm"
        :label="t('reservation.group.confirm')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!canConfirm"
        @click="confirm"
      />
    </template>
  </Dialog>
</template>
