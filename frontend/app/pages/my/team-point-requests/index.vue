<script setup lang="ts">
import type { LoyaltyAwardRequest, LoyaltyPage } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'] })
const api = useTeamLoyaltyApi()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const page = ref<LoyaltyPage<LoyaltyAwardRequest> | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
async function load(cursor?: string | null) {
  loading.value = true
  error.value = null
  try {
    page.value = await api.myRequests(cursor)
  } catch (e) {
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    loading.value = false
  }
}
useHead({ title: () => t('loyalty.requests.title') })
onMounted(() => load())
</script>
<template>
  <main class="mx-auto max-w-2xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.requests.title')" />
    <Button class="min-h-11" :label="t('loyalty.refresh')" @click="load()" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load()" />
    <template v-else-if="page">
      <DashboardEmptyState
        v-if="!page.data.length && !page.meta.hasNext"
        :message="t('loyalty.requests.empty')"
      />
      <NuxtLink
        v-for="item in page.data"
        :key="item.id"
        :to="`/my/team-point-requests/${item.id}`"
        class="block min-h-11"
      >
        <LoyaltyRequestDetail :request="item" />
      </NuxtLink>
      <Button
        v-if="page.meta.hasNext"
        class="min-h-11"
        :label="t(page.data.length ? 'loyalty.more' : 'loyalty.requests.moreCandidates')"
        @click="load(page.meta.nextCursor)"
      />
    </template>
  </main>
</template>
