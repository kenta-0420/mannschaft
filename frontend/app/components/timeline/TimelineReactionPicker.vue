<script setup lang="ts">
import { PRESET_EMOJIS } from '~/types/timeline'

const props = defineProps<{
  reactionSummary: Record<string, number>
  myReactions: string[]
}>()

const emit = defineEmits<{
  toggle: [emoji: string]
}>()

const showPicker = ref(false)

function isMyReaction(emoji: string): boolean {
  return props.myReactions.includes(emoji)
}

function onToggle(emoji: string) {
  emit('toggle', emoji)
  showPicker.value = false
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-1">
    <!-- 既存リアクション -->
    <button
      v-for="(count, emoji) in reactionSummary"
      :key="emoji"
      class="inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs transition-colors"
      :class="isMyReaction(String(emoji))
        ? 'border-primary bg-primary/10 text-primary'
        : 'border-surface-200 hover:border-surface-300'"
      @click="onToggle(String(emoji))"
    >
      <span>{{ emoji }}</span>
      <span class="font-medium">{{ count }}</span>
    </button>

    <!-- 追加ボタン -->
    <div class="relative">
      <button
        class="inline-flex h-6 w-6 items-center justify-center rounded-full border border-surface-300 text-xs text-surface-400 transition-colors hover:border-surface-300 hover:text-surface-600"
        @click="showPicker = !showPicker"
      >
        <i class="pi pi-plus text-[10px]" />
      </button>

      <!-- ピッカー -->
      <div
        v-if="showPicker"
        class="absolute bottom-full left-0 z-10 mb-1 flex gap-1 rounded-lg border border-surface-300 bg-surface-0 p-2 shadow-lg"
      >
        <button
          v-for="emoji in PRESET_EMOJIS"
          :key="emoji"
          class="flex h-8 w-8 items-center justify-center rounded-md text-lg transition-colors hover:bg-surface-100"
          @click="onToggle(emoji)"
        >
          {{ emoji }}
        </button>
      </div>
    </div>
  </div>
</template>
