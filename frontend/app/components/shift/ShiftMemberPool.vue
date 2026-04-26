<template>
  <div class="flex flex-col h-full border-l border-surface-200 bg-surface-50">
    <div class="p-3 border-b border-surface-200">
      <h3 class="font-semibold text-surface-700 mb-2">{{ $t('shift.board.memberPool') }}</h3>
      <InputText
        v-model="filterQuery"
        :placeholder="$t('button.search')"
        class="w-full text-sm"
        size="small"
      />
    </div>
    <div class="flex-1 overflow-y-auto p-2 space-y-1">
      <div
        v-for="member in filteredMembers"
        :key="member.userId"
        class="flex items-center gap-2 p-2 rounded-lg bg-white border border-surface-200 cursor-grab hover:border-primary-400 hover:shadow-sm transition-all"
        draggable="true"
        @dragstart="onDragStart($event, member.userId)"
      >
        <Avatar
          v-if="member.avatarUrl"
          :image="member.avatarUrl"
          size="small"
          shape="circle"
        />
        <Avatar v-else :label="member.displayName.charAt(0)" size="small" shape="circle" />
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium truncate">{{ member.displayName }}</p>
          <p v-if="member.remainingHours !== undefined" class="text-xs text-surface-500">
            残り {{ member.remainingHours }}h
          </p>
        </div>
      </div>
      <p v-if="filteredMembers.length === 0" class="text-sm text-surface-400 text-center py-4">
        {{ $t('label.noFollowing') }}
      </p>
    </div>
    <div class="p-3 border-t border-surface-200 text-xs text-surface-500 text-center">
      {{ $t('shift.board.dragHint') }}
    </div>
  </div>
</template>

<script setup lang="ts">
interface MemberPoolItem {
  userId: number
  displayName: string
  avatarUrl: string | null
  remainingHours?: number
  consecutiveDays?: number
}

interface Props {
  members: MemberPoolItem[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  dragStart: [userId: number]
}>()

const filterQuery = ref('')

const filteredMembers = computed(() => {
  if (!filterQuery.value.trim()) return props.members
  const q = filterQuery.value.toLowerCase()
  return props.members.filter((m) => m.displayName.toLowerCase().includes(q))
})

function onDragStart(event: DragEvent, userId: number): void {
  if (event.dataTransfer) {
    event.dataTransfer.setData('text/plain', String(userId))
    event.dataTransfer.effectAllowed = 'move'
  }
  emit('dragStart', userId)
}
</script>
