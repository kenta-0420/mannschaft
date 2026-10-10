<script setup lang="ts">
import type { RanchState } from '~/types/ranch'
const props = defineProps<{ state: RanchState }>()
const { t } = useI18n()
const affinity = computed(() => {
 const band = props.state.dinosaur?.affinityBand
 return band === 'NEUTRAL' || band === 'WARM' || band === 'CLOSE' ? t(`ranch.affinity.${band}`) : '—'
})
</script>
<template>
 <section v-if="state.owner && state.dinosaur" class="rounded-xl border p-4 space-y-3">
  <h2 class="font-semibold">{{ t('ranch.care.status') }}</h2>
  <dl class="grid gap-3 sm:grid-cols-2">
   <div><dt>{{ t('ranch.status.growth') }}</dt><dd>{{ state.dinosaur.xp }} XP</dd></div>
   <div><dt>{{ t('ranch.status.points') }}</dt><dd>{{ state.owner.balance }}</dd></div>
   <div><dt>{{ t('ranch.status.affinity') }}</dt><dd>{{ affinity }}</dd></div>
   <div><dt>{{ t('ranch.status.freeCare') }}</dt><dd>{{ t(state.featureStatus === 'AVAILABLE' ? 'ranch.status.careAvailable' : 'ranch.status.careStopped') }}</dd></div>
  </dl>
  <p v-if="state.owner.status === 'PAUSED'">{{ t('ranch.paused') }}</p>
  <template v-if="state.careBudget">
   <p>{{ t('ranch.status.careRemaining', { remaining: state.careBudget.remainingXp, cap: state.careBudget.weeklyCapXp }) }}</p>
   <p>{{ t('ranch.status.careUsed', { used: state.careBudget.awardedXp, amount: state.careBudget.amountXp }) }}</p>
  </template>
  <p>{{ t(`ranch.status.reward.${state.rewardsStatus}`) }}</p>
  <p>{{ t(state.deliveryPaused ? 'ranch.status.deliveryPaused' : 'ranch.status.deliveryActive') }}</p>
  <template v-if="state.weekBudget">
   <p>{{ t('ranch.status.pointRemaining', { remaining: state.weekBudget.remaining }) }}</p>
   <p>{{ t('ranch.status.personalReference', { required: state.weekBudget.personalRequiredCount, completed: state.weekBudget.personalCompletedCount }) }}</p>
   <p class="text-sm">{{ t('ranch.status.referenceNotice') }}</p>
  </template>
 </section>
</template>
