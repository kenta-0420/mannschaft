<script setup lang="ts">
const { t } = useI18n()
const appearanceStore = useAppearanceStore()
const notification = useNotification()

async function saveAppearance() {
  await appearanceStore.syncWithServer()
  notification.success(t('settings.appearance.save_success'))
}
</script>

<template>
  <SectionCard :title="$t('settings.appearance.section_title')">
    <div class="space-y-6">
      <ThemeSelector />
      <Divider />
      <BackgroundColorPicker />
      <Divider />
      <div class="flex items-center justify-between">
        <div>
          <label class="text-sm font-medium">{{ $t('settings.appearance.hide_chat_preview_label') }}</label>
          <p class="text-xs text-surface-500">{{ $t('settings.appearance.hide_chat_preview_description') }}</p>
        </div>
        <ToggleSwitch
          :model-value="appearanceStore.hideChatPreview"
          @update:model-value="(val: boolean) => appearanceStore.setHideChatPreview(val)"
        />
      </div>
      <div class="flex justify-end">
        <Button translate="no" :label="$t('settings.appearance.save_button')" icon="pi pi-check" @click="saveAppearance" />
      </div>
    </div>
  </SectionCard>
</template>
