<script setup lang="ts">
const appearanceStore = useAppearanceStore()

const themeOptions = [
  { label: 'ライト', value: 'LIGHT', icon: 'pi pi-sun' },
  { label: 'ダーク', value: 'DARK', icon: 'pi pi-moon' },
  { label: 'システム', value: 'SYSTEM', icon: 'pi pi-desktop' },
]

const selectedTheme = computed({
  get: () => appearanceStore.theme,
  set: (val: string) => {
    appearanceStore.setTheme(val as 'LIGHT' | 'DARK' | 'SYSTEM')
    appearanceStore.syncWithServer()
  },
})
</script>

<template>
  <div>
    <label class="mb-2 block text-sm font-medium">テーマ</label>
    <SelectButton
      v-model="selectedTheme"
      :options="themeOptions"
      option-label="label"
      option-value="value"
      :allow-empty="false"
    >
      <template #option="slotProps">
        <div class="flex items-center gap-2">
          <i :class="slotProps.option.icon" />
          <span>{{ slotProps.option.label }}</span>
        </div>
      </template>
    </SelectButton>
  </div>
</template>
