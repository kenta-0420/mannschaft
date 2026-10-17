<script setup lang="ts">
const appearanceStore = useAppearanceStore()
const { t } = useI18n()

const themeOptions = computed(() => [
  { label: t('appearance.themeLightLabel'), value: 'LIGHT', icon: 'pi pi-sun' },
  { label: t('appearance.themeDarkLabel'), value: 'DARK', icon: 'pi pi-moon' },
])

const selectedTheme = computed({
  get: () => appearanceStore.theme,
  set: (val: string) => {
    appearanceStore.setTheme(val as 'LIGHT' | 'DARK')
    appearanceStore.syncWithServer()
  },
})
</script>

<template>
  <div>
    <label class="mb-2 block text-sm font-medium">{{ t('appearance.themeLabel') }}</label>
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
