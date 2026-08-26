<script setup lang="ts">
/**
 * F20.1 課金機能の使い方ガイド（モーダル）。SecurityHelpDialog を金型に踏襲。
 * `/手助け` 方式: PageHeader の help フラグ + カード方式ガイド。
 */
const visible = defineModel<boolean>('visible', { default: false })

const props = withDefaults(
  defineProps<{
    variant?: 'plans' | 'manage' | 'betaPerks'
  }>(),
  { variant: 'plans' },
)

const { t } = useI18n()

const titleKey = computed(() => `billing.${props.variant}.help.title`)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t(titleKey)"
    class="w-full max-w-2xl"
    data-testid="billing-help-modal"
  >
    <div class="overflow-y-auto py-2">
      <BillingHelpContent :variant="variant" />
    </div>
    <template #footer>
      <Button
        :label="t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
