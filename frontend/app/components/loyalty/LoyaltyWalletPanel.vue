<script setup lang="ts">
import type { LoyaltyAccountSummary, LoyaltyPage } from '~/types/team-loyalty'
const api = useTeamLoyaltyApi()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const page = ref<LoyaltyPage<LoyaltyAccountSummary> | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
async function load(cursor?: string | null) {
  loading.value = true; error.value = null
  try { page.value = await api.accounts(cursor) }
  catch (e) { error.value = e; handleApiError(e) }
  finally { loading.value = false }
}
onMounted(() => load())
</script>
<template>
  <SectionCard :title="t('loyalty.wallet.title')" class="mb-5">
    <div class="mb-4 flex flex-wrap gap-3">
      <NuxtLink to="/my/member-qr" class="inline-flex min-h-11 items-center gap-2"><i class="pi pi-qrcode" />{{ t('loyalty.memberQr.title') }}</NuxtLink>
      <NuxtLink to="/my/team-point-requests" class="inline-flex min-h-11 items-center">{{ t('loyalty.requests.title') }}</NuxtLink>
    </div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load()" />
    <template v-else-if="page">
      <DashboardEmptyState v-if="!page.data.length" :message="t('loyalty.wallet.empty')" />
      <ul class="space-y-2"><li v-for="item in page.data" :key="item.accountId">
        <NuxtLink :to="`/my/team-points/${item.teamId}`" class="flex min-h-11 flex-wrap justify-between gap-2 py-2">
          <span>{{ item.teamName }}</span><span>{{ t('loyalty.balance', { points: item.balance }) }}</span>
        </NuxtLink>
      </li></ul>
      <Button v-if="page.meta.hasNext" class="min-h-11" :label="t('loyalty.more')" @click="load(page.meta.nextCursor)" />
    </template>
  </SectionCard>
</template>
