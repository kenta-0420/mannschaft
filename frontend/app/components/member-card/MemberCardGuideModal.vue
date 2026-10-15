<script setup lang="ts">
/** QR会員証管理の使い方を案内するモーダル。 */
defineProps<{ team: boolean }>()
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog v-model:visible="visible" modal :header="$t('memberCardGuide.title')" class="w-full max-w-2xl" data-testid="member-card-guide-modal">
    <div class="overflow-y-auto py-2"><MemberCardGuideContent :team="team" /></div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
