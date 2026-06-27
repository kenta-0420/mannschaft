<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('inbox.guide.title')"
    class="w-full max-w-2xl"
    data-testid="inbox-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <InboxGuideContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
