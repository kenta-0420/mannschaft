<template>
  <div
    class="relative group inline-flex items-center gap-1 rounded-full px-2 py-1 text-sm cursor-grab select-none transition-colors"
    :class="chipClass"
  >
    <span class="truncate max-w-24">{{ displayName }}</span>
    <button
      v-if="removable"
      type="button"
      class="ml-1 hover:text-red-500 transition-colors leading-none"
      :aria-label="$t('button.delete')"
      @click.stop="$emit('remove')"
    >
      &times;
    </button>
    <!-- 警告ツールチップ -->
    <span
      v-if="hasWarning && warningMessage"
      class="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 z-50 hidden group-hover:block bg-yellow-800 text-yellow-100 text-xs rounded px-2 py-1 whitespace-nowrap pointer-events-none"
    >
      {{ warningMessage }}
    </span>
  </div>
</template>

<script setup lang="ts">
interface Props {
  displayName: string
  hasWarning?: boolean
  warningMessage?: string
  removable?: boolean
}

const props = defineProps<Props>()

defineEmits<{
  remove: []
}>()

const chipClass = computed(() => ({
  'bg-surface-100 text-surface-700 border border-surface-300': !props.hasWarning,
  'bg-yellow-50 text-yellow-800 border-2 border-yellow-400': props.hasWarning,
}))
</script>
