<script setup lang="ts">
import { takeLoyaltyReceptionToken } from '~/utils/loyaltyReceptionToken'
import type { LoyaltyBootstrap, LoyaltySelfResult } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'] })
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const token = takeLoyaltyReceptionToken()
const data = ref<LoyaltyBootstrap | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
const mutation = useLoyaltyMutation<LoyaltySelfResult>()
async function load() {
  loading.value = true
  error.value = null
  try {
    if (token) data.value = await api.bootstrap(token)
  } catch (e) {
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    loading.value = false
  }
}
async function submit(retry = false) {
  if (!data.value || !token) return
  try {
    const preview = data.value.preview
    const result = retry
      ? await mutation.retry()
      : await mutation.start(
          {
            locationToken: token,
            ruleId: preview.rule.id,
            expectedRuleVersion: preview.rule.version,
            expectedProgramVersion: preview.programVersion,
          },
          (body, key) => api.selfAward(preview.teamId, body, key),
        )
    if (result) {
      notification.success(
        t(result.outcome === 'SUBMITTED' ? 'loyalty.requests.submitted' : 'loyalty.scan.success'),
      )
      if (result.outcome === 'AWARDED') await navigateTo(`/my/team-points/${preview.teamId}`)
    }
  } catch (e) {
    handleApiError(e, 'team-loyalty')
  }
}
useHead({ title: () => t('loyalty.self.confirm') })
onMounted(load)
</script>
<template>
  <main class="mx-auto max-w-xl space-y-4 p-4">
    <PageHeader :title="t('loyalty.self.confirm')" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <SectionCard v-else-if="!token" :title="t('loyalty.self.rescan')" />
    <LoyaltyRequestDetail
      v-else-if="mutation.result.value?.request"
      :request="mutation.result.value.request"
    />
    <template v-else-if="data">
      <LoyaltyAccountPanel :account="data.account" />
      <SectionCard :title="data.preview.rule.name">
        <p>{{ t('loyalty.rule.points', { points: data.preview.rule.points }) }}</p>
        <p>{{ t('loyalty.rule.frequencyDaily') }}</p>
        <p>
          {{
            t(`loyalty.mode.${data.preview.selfAwardMode === 'AUTO' ? 'auto' : 'staffApproval'}`)
          }}
        </p>
        <p v-if="data.preview.selfAwardMode === 'AUTO'">{{ t('loyalty.mode.autoWarning') }}</p>
        <p v-if="data.preview.isAlreadyAwardedToday">{{ t('loyalty.error.alreadyAwarded') }}</p>
        <p v-if="mutation.uncertain.value" role="alert">{{ t('loyalty.error.uncertain') }}</p>
        <Button
          class="mt-4 min-h-11 w-full"
          :label="t(mutation.uncertain.value ? 'loyalty.retrySame' : 'loyalty.confirm')"
          :loading="mutation.pending.value"
          :disabled="!data.preview.isEligibleForAward || data.preview.isAlreadyAwardedToday"
          @click="submit(mutation.uncertain.value)"
        />
      </SectionCard>
    </template>
    <NuxtLink to="/my/team-point-requests" class="inline-flex min-h-11 items-center">{{
      t('loyalty.requests.title')
    }}</NuxtLink>
  </main>
</template>
