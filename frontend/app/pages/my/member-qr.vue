<script setup lang="ts">
definePageMeta({ middleware: ['auth'] })
const { t } = useI18n()
useHead({ title: () => t('loyalty.memberQr.title') })
</script>
<template>
  <main class="mx-auto max-w-xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.memberQr.title')" />
    <LoyaltyMemberQrPanel />
    <NuxtLink to="/wallet" class="inline-flex min-h-11 items-center">{{ t('loyalty.wallet.title') }}</NuxtLink>
  </main>
</template>
