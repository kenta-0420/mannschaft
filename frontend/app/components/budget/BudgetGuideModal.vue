<script setup lang="ts">
/** 予算・会計の使い方。内容はBudgetGuideContentへ分離する。 */
const visible = defineModel<boolean>('visible', { required: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :pt="{ pcCloseButton: { root: { class: 'min-h-11 min-w-11' } } }"
    :header="$t('budget_guide.title')"
    class="w-full max-w-2xl"
    data-testid="budget-guide-modal"
  >
    <div class="overflow-y-auto py-2"><BudgetGuideContent /></div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        class="min-h-11 min-w-11"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
