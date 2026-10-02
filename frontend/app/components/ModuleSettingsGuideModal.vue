<script setup lang="ts">
/** 機能設定タブの使い方を案内するモーダル。v-model:visible で開閉。本体は ModuleSettingsGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('module_settings_guide.title')"
    class="w-full max-w-2xl"
    data-testid="module-settings-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ModuleSettingsGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
