<script setup lang="ts">
const { t } = useI18n()
const appearanceStore = useAppearanceStore()

// ライトモード用プリセット（既存）
const lightPresetColors = [
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

// ダークモード用プリセット（8色）
const darkPresetColors = [
  { labelKey: 'appearance.darkColor.charcoal', value: '#18181b' },
  { labelKey: 'appearance.darkColor.black', value: '#0a0a0a' },
  { labelKey: 'appearance.darkColor.graphite', value: '#27272a' },
  { labelKey: 'appearance.darkColor.slate', value: '#1e293b' },
  { labelKey: 'appearance.darkColor.navy', value: '#0f172a' },
  { labelKey: 'appearance.darkColor.forest', value: '#14241c' },
  { labelKey: 'appearance.darkColor.coffee', value: '#231a14' },
  { labelKey: 'appearance.darkColor.wine', value: '#2a1620' },
]

// 現在のモードに応じたプリセットと選択色
const presetColors = computed(() =>
  appearanceStore.isDark ? darkPresetColors : lightPresetColors,
)

const currentColor = computed(() =>
  appearanceStore.isDark ? appearanceStore.darkBgColor : appearanceStore.bgColor,
)

const labelKey = computed(() =>
  appearanceStore.isDark ? 'appearance.darkBgColorLabel' : 'appearance.bgColorLabel',
)

function selectColor(color: string) {
  if (appearanceStore.isDark) {
    appearanceStore.setDarkBgColor(color)
  }
  else {
    appearanceStore.setBgColor(color)
    appearanceStore.syncWithServer()
  }
}
</script>

<template>
  <div>
    <label class="mb-2 block text-sm font-medium">{{ t(labelKey) }}</label>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="color in presetColors"
        :key="color.value"
        class="h-8 w-8 rounded-full border-2 transition-transform hover:scale-110"
        :class="currentColor === color.value ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'"
        :style="{ backgroundColor: color.value }"
        :title="t(color.labelKey)"
        @click="selectColor(color.value)"
      />
    </div>
  </div>
</template>
