<script setup lang="ts">
import {
  subscribeLoyaltyReceptionToken,
  takeLoyaltyReceptionToken,
} from '~/utils/loyaltyReceptionToken'
import type { LoyaltyBootstrap, LoyaltySelfResult } from '~/types/team-loyalty'
definePageMeta({ middleware: ['auth'] })
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const token = ref<string | null>(null)
const data = ref<LoyaltyBootstrap | null>(null)
const loading = ref(true)
const error = shallowRef<unknown>(null)
const mutation = shallowRef(useLoyaltyMutation<LoyaltySelfResult>())
let generation = 0
let mounted = false
let unsubscribe: (() => void) | undefined
async function load() {
  const currentGeneration = ++generation
  const currentToken = token.value
  loading.value = true
  error.value = null
  data.value = null
  try {
    if (currentToken) {
      const preview = await api.bootstrap(currentToken)
      if (mounted && generation === currentGeneration) data.value = preview
    }
  } catch (e) {
    if (mounted && generation === currentGeneration) {
      error.value = e
      handleApiError(e, 'team-loyalty')
    }
  } finally {
    if (mounted && generation === currentGeneration) loading.value = false
  }
}
function receiveNewEntry() {
  const nextToken = takeLoyaltyReceptionToken()
  if (!nextToken) return
  generation++
  token.value = nextToken
  data.value = null
  error.value = null
  mutation.value = useLoyaltyMutation<LoyaltySelfResult>()
  loading.value = true
  if (mounted) void load()
}
async function submit(retry = false) {
  const currentData = data.value
  const currentToken = token.value
  const currentMutation = mutation.value
  const currentGeneration = generation
  if (!currentData || !currentToken) return
  try {
    const preview = currentData.preview
    const result = retry
      ? await currentMutation.retry()
      : await currentMutation.start(
          {
            locationToken: currentToken,
            ruleId: preview.rule.id,
            expectedRuleVersion: preview.rule.version,
            expectedProgramVersion: preview.programVersion,
          },
          (body, key) => api.selfAward(preview.teamId, body, key),
        )
    if (result && mounted && generation === currentGeneration) {
      notification.success(
        t(result.outcome === 'SUBMITTED' ? 'loyalty.requests.submitted' : 'loyalty.scan.success'),
      )
      if (result.outcome === 'AWARDED') await navigateTo(`/my/team-points/${preview.teamId}`)
    }
  } catch (e) {
    if (mounted && generation === currentGeneration) handleApiError(e, 'team-loyalty')
  }
}
useHead({ title: () => t('loyalty.self.confirm') })
onMounted(() => {
  mounted = true
  token.value = takeLoyaltyReceptionToken()
  unsubscribe = subscribeLoyaltyReceptionToken(receiveNewEntry)
  void load()
})
onUnmounted(() => {
  mounted = false
  generation++
  unsubscribe?.()
})
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
