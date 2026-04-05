<script setup lang="ts">
const appearanceStore = useAppearanceStore()

const presetColors = [
  { label: 'ホワイト', value: '#ffffff' },
  { label: 'クリーム', value: '#fef9ef' },
  { label: 'ラベンダー', value: '#f0ebf8' },
  { label: 'ミント', value: '#ecf7f0' },
  { label: 'スカイ', value: '#edf4fc' },
]

function selectColor(color: string) {
  appearanceStore.setBgColor(color)
  appearanceStore.syncWithServer()
}
</script>

<template>
  <div>
    <label class="mb-2 block text-sm font-medium">背景色</label>
    <div class="flex gap-3">
      <button
        v-for="color in presetColors"
        :key="color.value"
        class="h-10 w-10 rounded-full border-2 transition-transform hover:scale-110"
        :class="appearanceStore.bgColor === color.value ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'"
        :style="{ backgroundColor: color.value }"
        :title="color.label"
        @click="selectColor(color.value)"
      />
    </div>
  </div>
</template>
