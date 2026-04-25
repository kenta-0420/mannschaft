<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 自動割当結果バナー -->
    <ShiftAutoAssignResultBanner
      :pending-run="pendingRun"
      @confirm="onConfirmAutoAssign"
      @revoke="$emit('revokeAutoAssign')"
    />

    <!-- ボード本体 -->
    <div class="flex-1 overflow-auto">
      <div v-if="groupedSlots.length === 0" class="flex items-center justify-center h-64 text-surface-400">
        <p>{{ $t('shift.board.unassignedSlots', { count: 0 }) }}</p>
      </div>

      <table v-else class="min-w-full border-collapse text-sm">
        <thead class="sticky top-0 bg-surface-50 z-10">
          <tr>
            <th class="px-3 py-2 text-left text-surface-600 border-b border-surface-200 min-w-32">
              日付・時間帯
            </th>
            <th
              v-for="position in positions"
              :key="position.id"
              class="px-3 py-2 text-left text-surface-600 border-b border-surface-200 min-w-40"
            >
              <span
                v-if="position.color"
                class="inline-block w-3 h-3 rounded-full mr-1"
                :style="{ backgroundColor: position.color }"
              />
              {{ position.name }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="slot in groupedSlots"
            :key="slot.id"
            class="border-b border-surface-100 hover:bg-surface-50"
          >
            <td class="px-3 py-2 text-surface-600 whitespace-nowrap font-medium">
              <div>{{ formatSlotDate(slot.date) }}</div>
              <div class="text-xs text-surface-400">{{ slot.startTime }}〜{{ slot.endTime }}</div>
            </td>
            <td
              v-for="position in positions"
              :key="position.id"
              class="px-2 py-1 border-l border-surface-100"
            >
              <ShiftBoardCell
                v-if="slot.positionId === position.id"
                :slot-id="slot.id"
                :assignments="getAssignments(slot)"
                :warnings="getSlotWarnings(slot.id)"
                @drop="(userId) => $emit('dropUser', { fromSlotId: null, toSlotId: slot.id, userId })"
                @remove-user="(userId) => $emit('removeUser', { slotId: slot.id, userId })"
                @add-user="$emit('addUser', slot.id)"
              />
              <div v-else class="min-h-12 bg-surface-50 rounded opacity-30" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ShiftSlotResponse, ShiftPositionResponse, AssignmentRun, AssignmentWarning } from '~/types/shift'

interface Props {
  slots: ShiftSlotResponse[]
  positions: ShiftPositionResponse[]
  localAssignments: Record<number, number[]>
  memberMap: Record<number, { userId: number; displayName: string; avatarUrl: string | null }>
  pendingRun: AssignmentRun | null
  scheduleVersion?: number
}

const props = defineProps<Props>()

const emit = defineEmits<{
  dropUser: [payload: { fromSlotId: number | null; toSlotId: number; userId: number }]
  removeUser: [payload: { slotId: number; userId: number }]
  addUser: [slotId: number]
  confirmAutoAssign: [note: string | undefined]
  revokeAutoAssign: []
}>()

const { locale } = useI18n()

// スロットをそのまま並べ（日付順にソート済みと仮定）
const groupedSlots = computed(() => {
  return [...props.slots].sort((a, b) => {
    if (a.date !== b.date) return a.date.localeCompare(b.date)
    return a.startTime.localeCompare(b.startTime)
  })
})

function getAssignments(
  slot: ShiftSlotResponse,
): Array<{ userId: number; displayName: string; avatarUrl: string | null }> {
  const localUserIds = props.localAssignments[slot.id]
  if (localUserIds !== undefined) {
    return localUserIds.map((userId) => ({
      userId,
      displayName: props.memberMap[userId]?.displayName ?? String(userId),
      avatarUrl: props.memberMap[userId]?.avatarUrl ?? null,
    }))
  }
  return slot.assignedMembers
}

function getSlotWarnings(slotId: number): Array<{ userId: number; message: string }> {
  if (!props.pendingRun?.warnings) return []
  return props.pendingRun.warnings
    .filter((w: AssignmentWarning) => w.slotId === slotId && w.userId !== undefined)
    .map((w: AssignmentWarning) => ({ userId: w.userId!, message: w.message }))
}

function formatSlotDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(locale.value)
}

function onConfirmAutoAssign(note: string | undefined): void {
  emit('confirmAutoAssign', note)
}
</script>
