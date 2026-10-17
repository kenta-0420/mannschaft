<script setup lang="ts">
import type { LoyaltyCatalog } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'], key: (route) => route.path })
const context = useTeamLoyaltyContext()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const catalog = ref<LoyaltyCatalog | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
async function load() {
  loading.value = true
  error.value = null
  try {
    if (!(await context.load())) return
    catalog.value = await api.catalog(context.teamId.value!)
  } catch (e) {
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    loading.value = false
  }
}
useHead({ title: () => t('loyalty.scan.title') })
onMounted(load)
</script>
<template>
  <main class="mx-auto max-w-3xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.scan.title')" />
    <PageLoading v-if="loading && !catalog" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <template v-else-if="catalog">
      <NuxtLink
        v-if="context.access.isScopeAdmin.value"
        :to="`/teams/${context.slug.value}/settings/loyalty`"
        class="inline-flex min-h-11 items-center"
        >{{ t('loyalty.settings.title') }}</NuxtLink
      >
      <LoyaltyStaffScanner :catalog="catalog" @changed="load" />
      <LoyaltyStaffRequests :team-id="catalog.teamId" @changed="load" />
    </template>
  </main>
</template>
