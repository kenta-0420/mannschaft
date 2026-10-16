<script setup lang="ts">
import type { LoyaltyAccount, LoyaltyPage, LoyaltyTransaction } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'], key: (route) => route.path })
const route = useRoute()
const api = useTeamLoyaltyApi()
const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const account = ref<LoyaltyAccount | null>(null)
const history = ref<LoyaltyPage<LoyaltyTransaction> | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
async function load(cursor?: string | null) {
  loading.value = true
  error.value = null
  try {
    const teamId = Number(route.params.teamId)
    const [detail, transactions] = await Promise.all([
      api.account(teamId),
      api.transactions(teamId, cursor),
    ])
    account.value = detail
    history.value = transactions
  } catch (e) {
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    loading.value = false
  }
}
useHead({ title: () => t('loyalty.wallet.title') })
onMounted(() => load())
</script>
<template>
  <main class="mx-auto max-w-2xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.wallet.title')" />
    <Button class="min-h-11" :label="t('loyalty.refresh')" @click="load()" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load()" />
    <template v-else-if="account && history">
      <LoyaltyAccountPanel :account="account" />
      <SectionCard :title="t('loyalty.history.title')">
        <DashboardEmptyState v-if="!history.data.length" :message="t('loyalty.history.empty')" />
        <ul class="space-y-3">
          <li v-for="item in history.data" :key="item.id">
            <p>
              {{ item.label }} · {{ t(`loyalty.history.${item.kind.toLowerCase()}`) }} ·
              {{ item.delta }}
            </p>
            <p>{{ item.createdAt }} · {{ t('loyalty.balance', { points: item.balanceAfter }) }}</p>
          </li>
        </ul>
        <Button
          v-if="history.meta.hasNext"
          class="min-h-11"
          :label="t('loyalty.more')"
          @click="load(history.meta.nextCursor)"
        />
      </SectionCard>
    </template>
  </main>
</template>
