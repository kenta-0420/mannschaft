<script setup lang="ts">
import type { LoyaltyAwardRequest } from '~/types/team-loyalty'
defineProps<{ request: LoyaltyAwardRequest }>()
const { t } = useI18n()
const key = (value: string) => value.toLowerCase().replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase())
</script>
<template>
  <SectionCard :title="request.teamName">
    <p>{{ request.applicantDisplayName }}</p>
    <p>{{ request.locationName }} · {{ request.ruleName }} · {{ t('loyalty.balance', { points: request.points }) }}</p>
    <p role="status">{{ t(`loyalty.requests.status.${request.status.toLowerCase()}`) }}</p>
    <p v-if="request.reason">{{ t(`loyalty.requests.reason.${key(request.reason)}`) }}</p>
    <template v-if="request.status === 'PENDING'">
      <p class="my-4 break-all text-3xl font-bold tracking-wider">{{ t('loyalty.requests.code', { code: request.verificationCode }) }}</p>
      <p>{{ t('loyalty.requests.expiresAt', { datetime: request.expiresAt }) }}</p>
      <p>{{ t('loyalty.requests.showStaff') }}</p>
    </template>
    <slot />
  </SectionCard>
</template>
