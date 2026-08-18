<script setup lang="ts">
interface Target {
  userId: number
  displayName: string
  avatarUrl: string | null
  calendarColor: string | null
}

const props = withDefaults(defineProps<{
  targetMode?: 'ALL_MEMBERS' | 'SELECTED_MEMBERS'
  targetCount?: number
  targets?: Target[]
  compact?: boolean
}>(), {
  targetMode: 'ALL_MEMBERS',
  targetCount: 0,
  targets: () => [],
  compact: false,
})

const { t } = useI18n()
const previewTargets = computed(() => props.targets.slice(0, props.compact ? 2 : 3))
const remainingCount = computed(() => Math.max(0, (props.targetCount || props.targets.length) - previewTargets.value.length))
const isAllMembers = computed(() => props.targetMode !== 'SELECTED_MEMBERS')
const accessibleLabel = computed(() => {
  if (isAllMembers.value) return t('schedule.targetAudience.allMembers')
  if (props.targets.length > 0) return props.targets.map(target => target.displayName).join(', ')
  return t('schedule.targetAudience.selectedCount', { count: props.targetCount })
})
</script>

<template>
  <span
    class="inline-flex min-w-0 items-center gap-1 text-[10px] leading-none"
    :aria-label="accessibleLabel"
  >
    <template v-if="isAllMembers">
      <i class="pi pi-users shrink-0" aria-hidden="true" />
      <span class="truncate">{{ $t('schedule.targetAudience.allMembers') }}</span>
    </template>
    <template v-else-if="previewTargets.length">
      <span
        v-for="target in previewTargets"
        :key="target.userId"
        class="inline-flex max-w-24 items-center gap-0.5 rounded-full bg-white/30 px-1 py-0.5 dark:bg-black/15"
        :title="target.displayName"
      >
        <span
          class="h-2 w-2 shrink-0 rounded-full ring-1 ring-white/70 dark:ring-surface-900"
          :style="{ backgroundColor: target.calendarColor ?? '#64748b' }"
          aria-hidden="true"
        />
        <span class="truncate">{{ target.displayName }}</span>
      </span>
      <span v-if="remainingCount > 0" class="shrink-0">{{ $t('schedule.targetAudience.more', { count: remainingCount }) }}</span>
    </template>
    <template v-else>
      <i class="pi pi-users shrink-0" aria-hidden="true" />
      <span>{{ $t('schedule.targetAudience.selectedCount', { count: targetCount }) }}</span>
    </template>
  </span>
</template>
