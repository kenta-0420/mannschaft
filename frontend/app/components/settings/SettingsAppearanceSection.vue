<script setup lang="ts">
const appearanceStore = useAppearanceStore()
const notification = useNotification()

async function saveAppearance() {
  await appearanceStore.syncWithServer()
  notification.success('外観設定を保存しました')
}
</script>

<template>
  <SectionCard title="外観">
    <div class="space-y-6">
      <ThemeSelector />
      <Divider />
      <BackgroundColorPicker />
      <Divider />
      <div class="flex items-center justify-between">
        <div>
          <label class="text-sm font-medium">チャットプレビュー非表示</label>
          <p class="text-xs text-surface-500">通知バナーでチャットの内容を表示しない</p>
        </div>
        <ToggleSwitch
          :model-value="appearanceStore.hideChatPreview"
          @update:model-value="(val: boolean) => appearanceStore.setHideChatPreview(val)"
        />
      </div>
      <div class="flex justify-end">
        <Button label="外観を保存" icon="pi pi-check" @click="saveAppearance" />
      </div>
    </div>
  </SectionCard>
</template>
