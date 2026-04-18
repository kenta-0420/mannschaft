<script setup lang="ts">
const { t } = useI18n()
const appearanceStore = useAppearanceStore()

const presetColors = [
  { labelKey: 'appearance.color.cream', value: '#f3efe0' },
  { labelKey: 'appearance.color.white', value: '#ffffff' },
  { labelKey: 'appearance.color.gray', value: '#f5f5f5' },
  { labelKey: 'appearance.color.lavender', value: '#f0ebf8' },
  { labelKey: 'appearance.color.mint', value: '#ecf7f0' },
  { labelKey: 'appearance.color.sky', value: '#edf4fc' },
  { labelKey: 'appearance.color.peach', value: '#fde8e8' },
  { labelKey: 'appearance.color.sand', value: '#f5edd6' },
  { labelKey: 'appearance.color.sage', value: '#e8ede8' },
  { labelKey: 'appearance.color.slate', value: '#e8eaed' },
]

function selectColor(color: string) {
  appearanceStore.setBgColor(color)
  appearanceStore.syncWithServer()
}
</script>

<template>
  <div v-if="!appearanceStore.isDark">
    <label class="mb-2 block text-sm font-medium">{{ t('appearance.bgColorLabel') }}</label>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="color in presetColors"
        :key="color.value"
        class="h-8 w-8 rounded-full border-2 transition-transform hover:scale-110"
        :class="appearanceStore.bgColor === color.value ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'"
        :style="{ backgroundColor: color.value }"
        :title="t(color.labelKey)"
        @click="selectColor(color.value)"
      />
    </div>
  </div>
</template>
