<script setup lang="ts">
import type { Habitat } from '~/types/ranch'
definePageMeta({ middleware: 'auth' })
const { t } = useI18n(); useHead({ title: t('ranch.care.choose') })
const ranch = useRanchState(); const message = ref('')
async function random(habitat: Habitat) {
 const version = ranch.state.value?.owner?.version; if (!version) return
 try { await ranch.act(() => ranch.api.assignment({ method: 'HABITAT_RANDOM', habitat, version })); await navigateTo('/my/ranch') } catch { message.value = t('ranch.command.failed') }
}
onMounted(ranch.load)
</script>
<template>
 <div class="space-y-5">
  <PageHeader :title="t('ranch.care.choose')" back-to="/my/ranch" />
  <PageLoading v-if="ranch.loading.value" />
  <DashboardErrorState v-else-if="ranch.failed.value" @retry="ranch.load" />
  <SectionCard v-else :title="t('ranch.assignment.title')">
   <p class="mb-3">{{ t('ranch.assignment.description') }}</p>
   <div class="flex flex-wrap gap-3">
    <Button v-for="habitat in (['LAND','SEA','AIR'] as const)" :key="habitat" class="min-h-11" :label="t(`ranch.habitat.${habitat}`)" :disabled="!ranch.state.value?.assignment?.availableMethods.includes('HABITAT_RANDOM') || ranch.api.command.running.value" @click="random(habitat)" />
   </div>
   <p v-if="!ranch.state.value?.assignment?.availableMethods.includes('HABITAT_RANDOM')" class="mt-3">{{ t('ranch.unavailable') }}</p>
   <NuxtLink to="/my/ranch/diagnosis" class="flex min-h-11 items-center text-primary mt-3">{{ t('ranch.diagnosisResults.type64') }}</NuxtLink>
   <NuxtLink to="/my/ranch/birth-profile" class="flex min-h-11 items-center text-primary">{{ t('ranch.diagnosisResults.birthStyle') }}</NuxtLink>
   <NuxtLink to="/my/ranch/results" class="flex min-h-11 items-center text-primary">{{ t('ranch.assignment.fromResult') }}</NuxtLink>
   <p role="status">{{ message }}</p>
  </SectionCard>
 </div>
</template>
