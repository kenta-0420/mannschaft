<template>
  <div
    class="min-h-16 p-1 border border-surface-200 rounded-lg transition-colors"
    :class="dropZoneClass"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div class="flex flex-wrap gap-1">
      <ShiftMemberChip
        v-for="assignment in assignments"
        :key="assignment.userId"
        :display-name="assignment.displayName"
        :has-warning="hasWarning(assignment.userId)"
        :warning-message="getWarningMessage(assignment.userId)"
        removable
        draggable="true"
        @dragstart="onDragStart($event, assignment.userId)"
        @remove="$emit('removeUser', assignment.userId)"
      />
    </div>
    <button
      type="button"
      class="mt-1 text-xs text-primary-500 hover:text-primary-700 transition-colors"
      @click="$emit('addUser')"
    >
      + {{ $t('button.create') }}
    </button>
  </div>
</template>

<script setup lang="ts">
interface AssignedMember {
  userId: number
  displayName: string
  avatarUrl: string | null
}

interface WarningItem {
  userId: number
  message: string
}

interface Props {
  slotId: number
  assignments: AssignedMember[]
  warnings?: WarningItem[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  drop: [payload: { fromSlotId: number | null; userId: number }]
  removeUser: [userId: number]
  addUser: []
}>()

const sourceSlotType = 'application/x-mannschaft-shift-slot-id'
const isDragOver = ref(false)

const dropZoneClass = computed(() => ({
  'bg-primary-50 border-primary-400 border-dashed': isDragOver.value,
  'bg-white': !isDragOver.value,
}))

function hasWarning(userId: number): boolean {
  return (props.warnings ?? []).some((w) => w.userId === userId)
}

function getWarningMessage(userId: number): string | undefined {
  return (props.warnings ?? []).find((w) => w.userId === userId)?.message
}

function onDragOver(): void {
  isDragOver.value = true
}

function onDragLeave(): void {
  isDragOver.value = false
}

function onDragStart(event: DragEvent, userId: number): void {
  if (!event.dataTransfer) return
  event.dataTransfer.setData('text/plain', String(userId))
  event.dataTransfer.setData(sourceSlotType, String(props.slotId))
  event.dataTransfer.effectAllowed = 'move'
}

function onDrop(event: DragEvent): void {
  isDragOver.value = false
  const transfer = event.dataTransfer
  if (!transfer) return
  const userId = Number(transfer.getData('text/plain'))
  if (!Number.isSafeInteger(userId) || userId <= 0) return

  let fromSlotId: number | null = null
  if (Array.from(transfer.types).includes(sourceSlotType)) {
    fromSlotId = Number(transfer.getData(sourceSlotType))
    if (!Number.isSafeInteger(fromSlotId) || fromSlotId <= 0 || fromSlotId === props.slotId) return
  }
  emit('drop', { fromSlotId, userId })
}
</script>
