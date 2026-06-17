<script setup lang="ts">
import dayjs from 'dayjs'
import type { ReservationLineResponse, ReservationSlotResponse } from '~/types/reservation'

const props = defineProps<{
  teamId: string
}>()

const emit = defineEmits<{
  slotSelected: [slotId: number, lineId: number, lineName: string, date: string, startTime: string, endTime: string]
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const { userTimezone } = useDatetime()

interface LineOption { id: number; name: string }

const lines = ref<LineOption[]>([])
const slots = ref<ReservationSlotResponse[]>([])
const selectedDate = ref<Date | null>(new Date())
const selectedLineId = ref<number | null>(null)
const loading = ref(false)

const selectedLineName = computed(
  () => lines.value.find(l => l.id === selectedLineId.value)?.name ?? '',
)

async function loadLines() {
  const res = await reservationApi.getLines(props.teamId)
  lines.value = (res.data as ReservationLineResponse[])
    .filter(l => l.meta?.isActive)
    .map(l => ({ id: l.id ?? 0, name: l.meta?.name ?? '' }))
  if (lines.value.length > 0 && !selectedLineId.value) {
    selectedLineId.value = lines.value[0]!.id
  }
}

async function loadSlots() {
  if (!selectedDate.value || !selectedLineId.value) return
  loading.value = true
  try {
    // BE のスロット一覧は from/to（取得期間）が必須。単日表示なので from=to=選択日 を渡す。
    // スロットは BE 上でライン非依存のため、ラインは予約作成時の lineId としてのみ使う。
    const dateStr = dayjs(selectedDate.value).tz(userTimezone.value).format('YYYY-MM-DD')
    const res = await reservationApi.getSlots(props.teamId, { from: dateStr, to: dateStr })
    slots.value = (res.data as ReservationSlotResponse[]).filter(
      s => s.status?.slotStatus !== 'CLOSED',
    )
  }
  catch { slots.value = [] }
  finally { loading.value = false }
}

function isAvailable(slot: ReservationSlotResponse): boolean {
  return slot.status?.slotStatus === 'AVAILABLE'
}

function selectSlot(slot: ReservationSlotResponse) {
  if (!isAvailable(slot)) return
  emit(
    'slotSelected',
    slot.id ?? 0,
    selectedLineId.value ?? 0,
    selectedLineName.value,
    slot.basic?.slotDate ?? '',
    slot.basic?.startTime ?? '',
    slot.basic?.endTime ?? '',
  )
}

watch([selectedDate, selectedLineId], loadSlots)
onMounted(async () => { await loadLines(); await loadSlots() })
</script>

<template>
  <div class="space-y-4">
    <div class="grid grid-cols-2 gap-3">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.date') }}</label>
        <DatePicker v-model="selectedDate" date-format="yy/mm/dd" class="w-full" show-icon />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.line') }}</label>
        <Select
          v-model="selectedLineId"
          :options="lines"
          option-label="name"
          option-value="id"
          class="w-full"
          :placeholder="t('reservation.placeholder.select')"
        />
      </div>
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 4" :key="i" height="3rem" />
    </div>
    <div v-else-if="slots.length > 0" class="grid grid-cols-3 gap-2 md:grid-cols-4">
      <button
        v-for="slot in slots"
        :key="slot.id"
        class="rounded-lg border p-3 text-center transition-all"
        :class="isAvailable(slot)
          ? 'cursor-pointer border-surface-200 hover:border-primary hover:bg-primary/5 dark:border-surface-600'
          : 'cursor-not-allowed border-surface-100 bg-surface-50 opacity-50 dark:border-surface-600'"
        @click="selectSlot(slot)"
      >
        <p class="text-sm font-medium">{{ slot.basic?.startTime }} - {{ slot.basic?.endTime }}</p>
        <p class="text-xs" :class="isAvailable(slot) ? 'text-green-600' : 'text-red-500'">
          {{ isAvailable(slot) ? t('reservation.slot.available') : t('reservation.slot.full') }}
        </p>
      </button>
    </div>
    <DashboardEmptyState v-else icon="pi pi-calendar-times" :message="t('reservation.empty.no_available_slots')" />
  </div>
</template>
