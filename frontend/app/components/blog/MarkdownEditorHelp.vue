<script setup lang="ts">
defineProps<{
  showHelp: boolean
}>()

defineEmits<{
  'update:showHelp': [value: boolean]
}>()
</script>

<template>
  <Transition name="fade">
    <div
      v-if="showHelp"
      class="help-backdrop absolute inset-0 z-20"
      @click="$emit('update:showHelp', false)"
    />
  </Transition>

  <Transition name="help-slide">
    <div
      v-if="showHelp"
      class="help-panel absolute inset-y-0 right-0 z-30 flex flex-col overflow-hidden"
    >
      <div class="help-header flex items-center justify-between px-4 py-3">
        <div class="flex items-center gap-2">
          <span
            class="flex h-5 w-5 items-center justify-center rounded-full bg-green-500 text-xs font-bold text-white"
            >?</span
          >
          <span class="text-sm font-bold text-green-800">使い方ガイド</span>
        </div>
        <button
          type="button"
          class="flex h-6 w-6 items-center justify-center rounded-full text-surface-400 transition-all hover:bg-green-100 hover:text-green-700"
          @click="$emit('update:showHelp', false)"
        >
          <i class="pi pi-times text-xs" />
        </button>
      </div>

      <MarkdownEditorHelpContent />
    </div>
  </Transition>
</template>

<style scoped>
.help-panel {
  width: 300px;
  background: #ffffff;
  border-left: 1.5px solid #bbf7d0;
  box-shadow: -4px 0 24px rgba(22, 163, 74, 0.1);
}
:global(.dark) .help-panel {
  background: #0f1f14;
  border-left-color: #166534;
}

.help-header {
  background: linear-gradient(135deg, #f0fdf4, #ecfdf5);
  border-bottom: 1px solid #bbf7d0;
  flex-shrink: 0;
}

.help-backdrop {
  background: rgba(0, 0, 0, 0.15);
  cursor: pointer;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.help-slide-enter-active,
.help-slide-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.help-slide-enter-from,
.help-slide-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
