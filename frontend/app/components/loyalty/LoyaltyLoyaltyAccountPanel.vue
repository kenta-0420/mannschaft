<script setup lang="ts">
import type { LoyaltyAccount } from '~/types/team-loyalty'
defineProps<{ account: LoyaltyAccount }>()
const { t } = useI18n()
</script>
<template>
  <SectionCard :title="account.teamName">
    <p class="text-2xl font-semibold" data-testid="loyalty-balance">{{ t('loyalty.balance', { points: account.balance }) }}</p>
    <p>{{ t('loyalty.lifetime', { points: account.lifetimeEarned }) }}</p>
    <p v-if="!account.isProgramEnabled">{{ t('loyalty.program.disabled') }}</p>
    <ul class="mt-4 space-y-4">
      <li v-for="reward in account.rewards" :key="reward.id">
        <h3 class="font-semibold">{{ reward.name }}</h3>
        <p v-if="reward.description">{{ reward.description }}</p>
        <p>{{ t('loyalty.reward.required', { points: reward.requiredPoints }) }}</p>
        <progress class="w-full" :value="Math.min(account.balance, reward.requiredPoints)" :max="reward.requiredPoints" :aria-label="reward.name" />
        <p>{{ t('loyalty.remaining', { points: reward.remainingPoints }) }}</p>
      </li>
    </ul>
  </SectionCard>
</template>
