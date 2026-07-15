<script setup lang="ts">
/**
 * F20.1 U-5: 個人課金管理。
 * USER スコープは常に本人固定（操作制限なし＝全操作可）。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const showHelp = ref(false)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('billing.manage.personalTitle')" help @help="showHelp = true" />
    <BillingHelpDialog v-model:visible="showHelp" variant="manage" />

    <BillingManagePanel scope-kind="USER" scope-id="" :can-manage="true" />
  </div>
</template>
