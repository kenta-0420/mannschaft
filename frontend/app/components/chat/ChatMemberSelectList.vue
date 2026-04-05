<script setup lang="ts">
import type { MemberResponse } from '~/types/member'

defineProps<{
  members: MemberResponse[]
  loading: boolean
  isSelected: (m: MemberResponse) => boolean
  maxHeight?: string
  spinnerSize?: string
}>()

const emit = defineEmits<{
  toggle: [member: MemberResponse]
}>()
</script>

<template>
  <div
    class="overflow-y-auto rounded-lg border border-surface-300 dark:border-surface-600"
    :class="maxHeight ?? 'max-h-64'"
  >
    <div v-if="loading" class="flex justify-center py-6">
      <ProgressSpinner :style="{ width: spinnerSize ?? '30px', height: spinnerSize ?? '30px' }" />
    </div>
    <div
      v-else-if="members.length === 0"
      class="py-6 text-center text-sm text-surface-400"
    >
      メンバーが見つかりません
    </div>
    <button
      v-for="m in members"
      :key="m.userId"
      class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
      :class="isSelected(m) ? 'bg-primary/10' : ''"
      @click="emit('toggle', m)"
    >
      <Avatar
        :image="m.avatarUrl ?? undefined"
        :label="m.avatarUrl ? undefined : m.displayName.charAt(0)"
        shape="circle"
        size="small"
      />
      <span class="flex-1 truncate text-sm">{{ m.displayName }}</span>
      <i v-if="isSelected(m)" class="pi pi-check-circle text-primary" />
    </button>
  </div>
</template>
