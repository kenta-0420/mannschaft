<script setup lang="ts">
import type { LoyaltyAwardRequest } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'] })
const route = useRoute()
const api = useTeamLoyaltyApi()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const request = ref<LoyaltyAwardRequest | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
const mutation = useLoyaltyMutation<LoyaltyAwardRequest>()
async function load() {
  loading.value = true; error.value = null
  try { request.value = await api.myRequest(String(route.params.requestId)) }
  catch (e) { error.value = e; handleApiError(e) }
  finally { loading.value = false }
}
async function cancel() {
  if (!request.value) return
  try {
    if (mutation.uncertain.value) await mutation.retry()
    else await mutation.start({ expectedRequestVersion: request.value.version }, (body, key) => api.cancel(request.value!.id, body, key))
    await load()
  } catch (e) { handleApiError(e) }
}
useHead({ title: () => t('loyalty.requests.title') })
onMounted(load)
</script>
<template>
  <main class="mx-auto max-w-xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.requests.title')" />
    <Button class="min-h-11" :label="t('loyalty.refresh')" @click="load" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <LoyaltyRequestDetail v-else-if="request" :request="request">
      <p v-if="mutation.uncertain.value" role="alert">{{ t('loyalty.error.uncertain') }}</p>
      <Button
v-if="request.canCancel || mutation.uncertain.value" class="mt-4 min-h-11" :loading="mutation.pending.value"
        :label="t(mutation.uncertain.value ? 'loyalty.retrySame' : 'loyalty.requests.cancel')" @click="cancel" />
      <NuxtLink v-if="request.transactionId" :to="`/my/team-points/${request.teamId}`" class="flex min-h-11 items-center">{{ t('loyalty.wallet.title') }}</NuxtLink>
    </LoyaltyRequestDetail>
  </main>
</template>
