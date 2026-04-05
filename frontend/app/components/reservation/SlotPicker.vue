<script setup lang="ts">
const props = defineProps<{
  teamId: number
}>()

const emit = defineEmits<{
  slotSelected: [slotId: number, lineName: string, date: string, startTime: string, endTime: string]
}>()

const reservationApi = useReservationApi()

interface Line { id: number; name: string; isActive: boolean }
interface Slot { id: number; lineId: number; lineName: string; date: string; startTime: string; endTime: string; capacity: number; bookedCount: number; isClosed: boolean }

const lines = ref<Line[]>([])
const slots = ref<Slot[]>([])
const selectedDate = ref<Date | null>(new Date())
const selectedLineId = ref<number | null>(null)
const loading = ref(false)

async function loadLines() {
  const res = await reservationApi.getLines(props.teamId)
  lines.value = (res.data as Line[]).filter(l => l.isActive)
  if (lines.value.length > 0 && !selectedLineId.value) {
    selectedLineId.value = lines.value[0].id
  }
}

async function loadSlots() {
  if (!selectedDate.value || !selectedLineId.value) return
  loading.value = true
  try {
    const dateStr = selectedDate.value.toISOString().split('T')[0]
    const res = await reservationApi.getSlots(props.teamId, { date: dateStr, lineId: selectedLineId.value })
    slots.value = (res.data as Slot[]).filter(s => !s.isClosed)
  }
  catch { slots.value = [] }
  finally { loading.value = false }
}

function isAvailable(slot: Slot): boolean {
  return slot.bookedCount < slot.capacity
}

function selectSlot(slot: Slot) {
  if (!isAvailable(slot)) return
  const line = lines.value.find(l => l.id === slot.lineId)
  emit('slotSelected', slot.id, line?.name ?? '', slot.date, slot.startTime, slot.endTime)
}

watch([selectedDate, selectedLineId], loadSlots)
onMounted(async () => { await loadLines(); await loadSlots() })
</script>

<template>
  <div class="space-y-4">
    <div class="grid grid-cols-2 gap-3">
      <div>
        <label class="mb-1 block text-sm font-medium">日付</label>
        <DatePicker v-model="selectedDate" date-format="yy/mm/dd" class="w-full" show-icon />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">ライン</label>
        <Select
          v-model="selectedLineId"
          :options="lines"
          option-label="name"
          option-value="id"
          class="w-full"
          placeholder="選択してください"
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
        <p class="text-sm font-medium">{{ slot.startTime }} - {{ slot.endTime }}</p>
        <p class="text-xs" :class="isAvailable(slot) ? 'text-green-600' : 'text-red-500'">
          残{{ slot.capacity - slot.bookedCount }}/{{ slot.capacity }}
        </p>
      </button>
    </div>
    <DashboardEmptyState v-else icon="pi pi-calendar-times" message="この日の空き枠はありません" />
  </div>
</template>
