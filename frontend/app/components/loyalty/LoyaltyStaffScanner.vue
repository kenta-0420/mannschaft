<script setup lang="ts">
import type { LoyaltyCatalog, LoyaltyScanPreview, LoyaltyRedemptionPreview, LoyaltyTransaction } from '~/types/team-loyalty'
const props = defineProps<{ catalog: LoyaltyCatalog }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const mode = ref<'AWARD' | 'REDEEM'>('AWARD')
const rewardId = ref<string>('')
const token = ref('')
const preview = ref<LoyaltyScanPreview | LoyaltyRedemptionPreview | null>(null)
const loading = ref(false)
const mutation = useLoyaltyMutation<LoyaltyTransaction>()
const canConfirm = computed(() => preview.value && ('canRedeem' in preview.value ? preview.value.canRedeem : preview.value.isEligibleForAward))
async function scanned(qrToken: string) {
  if (mutation.uncertain.value || mutation.pending.value || loading.value || preview.value) return
  token.value = qrToken
  loading.value = true
  try {
    preview.value = mode.value === 'AWARD' && props.catalog.rule
      ? await api.scan(props.catalog.teamId, { qrToken, ruleId: props.catalog.rule.id })
      : await api.redemptionPreview(props.catalog.teamId, { qrToken, rewardId: rewardId.value })
  } catch (e) { handleApiError(e) }
  finally { loading.value = false }
}
async function confirm() {
  if (!preview.value) return
  try {
    let result: LoyaltyTransaction | undefined
    if (mutation.uncertain.value) result = await mutation.retry()
    else if ('rule' in preview.value) {
      result = await mutation.start({ qrToken: token.value, ruleId: preview.value.rule.id,
        expectedRuleVersion: preview.value.rule.version, expectedProgramVersion: preview.value.programVersion,
      }, (body, key) => api.award(props.catalog.teamId, body, key))
    } else {
      result = await mutation.start({ qrToken: token.value, rewardId: preview.value.reward.id,
        expectedRewardVersion: preview.value.reward.version, expectedProgramVersion: preview.value.programVersion,
      }, (body, key) => api.redeem(props.catalog.teamId, body, key))
    }
    if (result) { notification.success(t('loyalty.scan.success')); emit('changed') }
  } catch (e) { handleApiError(e) }
}
function reset() { if (!mutation.uncertain.value) { token.value = ''; preview.value = null; mutation.result.value = null } }
</script>
<template>
  <SectionCard :title="t('loyalty.scan.title')">
    <p class="mb-3 font-semibold">{{ catalog.teamName }}</p>
    <p v-if="!catalog.isEnabled">{{ t('loyalty.program.disabled') }}</p>
    <template v-else>
      <div v-if="!preview" class="space-y-3">
        <label class="flex min-h-11 items-center gap-3"><RadioButton v-model="mode" input-id="loyalty-award" value="AWARD" />{{ t('loyalty.history.award') }}</label>
        <label class="flex min-h-11 items-center gap-3"><RadioButton v-model="mode" input-id="loyalty-redeem" value="REDEEM" />{{ t('loyalty.history.redeem') }}</label>
        <p v-if="mode === 'AWARD' && catalog.rule">{{ catalog.rule.name }} · {{ t('loyalty.rule.points', { points: catalog.rule.points }) }}</p>
        <Select v-if="mode === 'REDEEM'" v-model="rewardId" :options="catalog.rewards" option-label="name" option-value="id" :aria-label="t('loyalty.reward.title')" class="w-full" />
        <LoyaltyQrScanner :disabled="loading || (mode === 'REDEEM' ? !rewardId : !catalog.rule)" @scanned="scanned" />
      </div>
      <PageLoading v-if="loading" />
      <div v-if="preview" class="space-y-3" aria-live="polite">
        <p>{{ preview.displayName }}</p>
        <p>{{ t('loyalty.balance', { points: mutation.result.value?.balanceAfter ?? preview.balance }) }}</p>
        <p v-if="'rule' in preview">{{ t('loyalty.scan.confirmAward', { team: catalog.teamName, points: preview.rule.points }) }}</p>
        <p v-else>{{ t('loyalty.reward.confirm', { reward: preview.reward.name, points: preview.reward.requiredPoints }) }}</p>
        <p v-if="mutation.uncertain.value" role="alert">{{ t('loyalty.error.uncertain') }}</p>
        <p v-if="mutation.result.value" role="status">{{ t('loyalty.scan.success') }}</p>
        <Button v-else class="min-h-11" :disabled="!canConfirm" :loading="mutation.pending.value" :label="t(mutation.uncertain.value ? 'loyalty.retrySame' : 'loyalty.confirm')" @click="confirm" />
        <Button class="min-h-11" :disabled="mutation.uncertain.value || mutation.pending.value" :label="t('loyalty.scan.next')" severity="secondary" @click="reset" />
      </div>
    </template>
  </SectionCard>
</template>
