<script setup lang="ts">
import dayjs from 'dayjs'
import type { ReservationSlotResponse } from '~/types/reservation'

/**
 * 管理者向け予約枠（Slot）管理コンポーネント。
 * 枠の一覧表示・作成・編集・クローズ・再開を行う。
 * SlotFormDialog を通じて承認モードセレクタを提供する。
 */
const props = defineProps<{
  teamId: string
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
// 多重防御（defense-in-depth）: 親タブの v-if に加え、破壊的操作ボタンを本コンポーネントでも
// ロールで制御する。BE が本防御線だが、別画面から再利用された際の誤表示を防ぐ。
const { isAdmin, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

const slots = ref<ReservationSlotResponse[]>([])
const loading = ref(true)
const selectedDate = ref<string>(dayjs().format('YYYY-MM-DD'))

// フォームダイアログ制御
const showFormDialog = ref(false)
const editingSlot = ref<ReservationSlotResponse | null>(null)

async function loadSlots() {
  loading.value = true
  try {
    const res = await reservationApi.getSlots(props.teamId, {
      from: selectedDate.value,
      to: selectedDate.value,
    })
    slots.value = res.data as ReservationSlotResponse[]
  }
  catch {
    slots.value = []
    notification.error(t('reservation.slot_manager.message.load_failed'))
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  editingSlot.value = null
  showFormDialog.value = true
}

function openEdit(slot: ReservationSlotResponse) {
  editingSlot.value = slot
  showFormDialog.value = true
}

async function onSaved() {
  await loadSlots()
}

async function toggleClose(slot: ReservationSlotResponse) {
  if (!slot.id) return
  try {
    if (slot.status?.slotStatus === 'CLOSED') {
      await reservationApi.reopenSlot(props.teamId, slot.id)
      notification.success(t('reservation.slot_manager.message.reopened'))
    }
    else {
      await reservationApi.closeSlot(props.teamId, slot.id)
      notification.success(t('reservation.slot_manager.message.closed'))
    }
    await loadSlots()
  }
  catch {
    notification.error(t('reservation.slot_manager.message.status_change_failed'))
  }
}

/** 承認モードの表示ラベル（null=チーム設定） */
function approvalModeLabel(slot: ReservationSlotResponse): string {
  const mode = slot.policy?.approvalMode
  if (mode === 'AUTO') return t('reservation.slot_form.approval_mode.option_auto')
  if (mode === 'MANUAL') return t('reservation.slot_form.approval_mode.option_manual')
  return t('reservation.slot_form.approval_mode.option_inherit')
}

watch(selectedDate, loadSlots)
onMounted(async () => {
  await loadPermissions()
  await loadSlots()
})
</script>

<template>
  <div>
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h3 class="text-lg font-semibold">{{ t('reservation.slot_manager.title') }}</h3>
      <div class="flex items-center gap-2">
        <InputText
          v-model="selectedDate"
          type="date"
          class="w-40"
        />
        <Button
          v-if="isAdmin"
          :label="t('reservation.slot_manager.button.add_slot')"
          icon="pi pi-plus"
          size="small"
          @click="openCreate"
        />
      </div>
    </div>

    <!-- 一覧 -->
    <div v-if="loading">
      <Skeleton v-for="i in 3" :key="i" height="3.5rem" class="mb-2" />
    </div>
    <div v-else-if="slots.length > 0" class="space-y-2">
      <div
        v-for="slot in slots"
        :key="slot.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
        :class="slot.status?.slotStatus === 'CLOSED' ? 'opacity-60' : ''"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ slot.basic?.startTime?.substring(0, 5) }} - {{ slot.basic?.endTime?.substring(0, 5) }}
            <span v-if="slot.basic?.title" class="ml-2 text-sm text-surface-500">{{ slot.basic.title }}</span>
          </p>
          <div class="mt-0.5 flex flex-wrap gap-2 text-xs text-surface-500">
            <span>{{ approvalModeLabel(slot) }}</span>
            <span v-if="slot.status?.bookedCount != null && slot.status?.capacity != null">
              {{ t('reservation.slot_manager.booked_capacity', { count: slot.status.bookedCount, capacity: slot.status.capacity }) }}
            </span>
            <span v-else-if="slot.status?.bookedCount != null">
              {{ t('reservation.slot_manager.booked_count', { count: slot.status.bookedCount }) }}
            </span>
          </div>
        </div>
        <Tag
          :value="slot.status?.slotStatus === 'CLOSED'
            ? t('reservation.slot_manager.status.closed')
            : slot.status?.slotStatus === 'FULL'
              ? t('reservation.slot_manager.status.full')
              : t('reservation.slot_manager.status.available')"
          :severity="slot.status?.slotStatus === 'CLOSED' ? 'secondary'
            : slot.status?.slotStatus === 'FULL' ? 'warn' : 'success'"
        />
        <Button
          v-if="isAdmin"
          icon="pi pi-pencil"
          text
          rounded
          size="small"
          :title="t('reservation.slot_manager.button.edit')"
          @click="openEdit(slot)"
        />
        <Button
          v-if="isAdmin"
          :icon="slot.status?.slotStatus === 'CLOSED' ? 'pi pi-play' : 'pi pi-pause'"
          text
          rounded
          size="small"
          :severity="slot.status?.slotStatus === 'CLOSED' ? 'success' : 'warn'"
          :title="slot.status?.slotStatus === 'CLOSED'
            ? t('reservation.slot_manager.button.reopen')
            : t('reservation.slot_manager.button.close')"
          @click="toggleClose(slot)"
        />
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-calendar-times"
      :message="t('reservation.slot_manager.empty.no_slots')"
    />

    <!-- 枠作成・編集ダイアログ -->
    <SlotFormDialog
      v-model:visible="showFormDialog"
      :team-id="teamId"
      :editing-slot="editingSlot"
      @saved="onSaved"
    />
  </div>
</template>
