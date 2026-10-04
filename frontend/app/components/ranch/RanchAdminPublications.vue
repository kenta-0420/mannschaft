<script setup lang="ts">
import type { RanchCareRulePublicationRequest, RanchPolicyPublicationRequest, RanchPublicationAck, RanchRewardSourceType } from '~/types/ranch-admin'

const { t } = useI18n()
const api = useRanchAdminApi()
const emit = defineEmits<{ saved: [RanchPublicationAck<RanchCareRulePublicationRequest | RanchPolicyPublicationRequest>] }>()
const message = ref('')
const busy = computed(() => api.command.running.value || !!api.command.pending.value)
const careFields = ['amountXp', 'weeklyCapXp', 'juvenileXp', 'adultXp'] as const
const care = reactive<RanchCareRulePublicationRequest>({ effectiveAt: '', amountXp: '', weeklyCapXp: '', juvenileXp: '', adultXp: '', reasonCode: '' })
const sourceTypes: RanchRewardSourceType[] = ['ATTENDANCE_RESPONSE', 'TIMELINE_ORIGINAL', 'BLOG_FIRST_PUBLISH', 'PERSONAL_RECALL_COMPLETE']
const policySources = reactive(sourceTypes.map(sourceType => ({ sourceType, enabled: false, amountPoints: '', countLimit: '' })))
const deliveryFields = ['batchSize', 'leaseSeconds', 'maxAttempts', 'initialBackoffSeconds', 'maxBackoffSeconds'] as const
const delivery = reactive({ batchSize: '', leaseSeconds: '', maxAttempts: '', initialBackoffSeconds: '', maxBackoffSeconds: '' })
const policy = reactive({ effectiveAt: '', enabled: false, globalWeeklyCap: '', reasonCode: '' })

function positiveDecimal(value: string, maximum = 9223372036854775807n) {
 return /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= maximum
}
function reasonValid(value: string) { return value === value.trim() && value.length >= 1 && value.length <= 80 }
function weekBoundary(value: string) {
 if (!/^\d{4}-\d{2}-\d{2}T00:00:00Z$/.test(value)) return false
 const date = new Date(value)
 return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) + 'Z' === value && date.getUTCDay() === 1
}
// 将来週・登録master・配送の測定boundはサーバーが再検査する。画面の判定を公開許可にしない。
const careValid = computed(() => weekBoundary(care.effectiveAt) && reasonValid(care.reasonCode)
 && careFields.every(key => positiveDecimal(care[key])) && BigInt(care.juvenileXp) < BigInt(care.adultXp))
const policyValid = computed(() => weekBoundary(policy.effectiveAt) && reasonValid(policy.reasonCode)
 && positiveDecimal(policy.globalWeeklyCap)
 && policySources.every(source => positiveDecimal(source.amountPoints) && positiveDecimal(source.countLimit, 2147483647n))
 && (!policy.enabled || policySources.some(source => source.sourceType === 'PERSONAL_RECALL_COMPLETE' && source.enabled))
 && deliveryFields.every(key => positiveDecimal(delivery[key], 2147483647n))
 && BigInt(delivery.initialBackoffSeconds) <= BigInt(delivery.maxBackoffSeconds))

async function publishCare() {
 if (busy.value || !careValid.value) return
 try {
  const saved = await api.publishCare({ ...care })
  if (api.isCurrent()) { emit('saved', saved); message.value = t('ranch.admin.saved') }
 } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
async function publishPolicy() {
 if (busy.value || !policyValid.value) return
 const body: RanchPolicyPublicationRequest = { ...policy,
  sources: policySources.map(source => ({ ...source, countLimit: Number(source.countLimit) })),
  delivery: { batchSize: Number(delivery.batchSize), leaseSeconds: Number(delivery.leaseSeconds), maxAttempts: Number(delivery.maxAttempts), initialBackoffSeconds: Number(delivery.initialBackoffSeconds), maxBackoffSeconds: Number(delivery.maxBackoffSeconds) },
 }
 try {
  const saved = await api.publishPolicy(body)
  if (api.isCurrent()) { emit('saved', saved); message.value = t('ranch.admin.saved') }
 } catch { if (api.isCurrent()) message.value = t('ranch.command.failed') }
}
</script>

<template>
 <section class="space-y-5 rounded-xl border p-4">
  <p>{{ t('ranch.admin.publicationNotice') }}</p>
  <form aria-labelledby="ranch-admin-care-publication-heading" class="space-y-3" @submit.prevent="publishCare">
   <h2 id="ranch-admin-care-publication-heading" class="font-semibold">{{ t('ranch.admin.publishCare') }}</h2>
   <fieldset :disabled="busy" class="grid gap-3 sm:grid-cols-2">
    <label class="grid gap-1">{{ t('ranch.admin.effectiveAt') }}<input v-model="care.effectiveAt" name="effectiveAt" required placeholder="2031-01-06T00:00:00Z" class="min-h-11 rounded border bg-transparent p-2"></label>
    <label v-for="key in careFields" :key="key" class="grid gap-1">{{ t(`ranch.admin.${key}`) }}<input v-model="care[key]" :name="key" required inputmode="numeric" pattern="[1-9][0-9]{0,18}" maxlength="19" class="min-h-11 rounded border bg-transparent p-2"></label>
    <label class="grid gap-1">{{ t('ranch.admin.reason') }}<input v-model="care.reasonCode" name="reasonCode" required maxlength="80" class="min-h-11 rounded border bg-transparent p-2"></label>
    <Button type="submit" :disabled="!careValid" :label="t('ranch.admin.publishCare')" class="min-h-11" />
   </fieldset>
  </form>
  <form aria-labelledby="ranch-admin-policy-publication-heading" class="space-y-3" @submit.prevent="publishPolicy">
   <h2 id="ranch-admin-policy-publication-heading" class="font-semibold">{{ t('ranch.admin.publishPolicy') }}</h2>
   <fieldset :disabled="busy" class="grid gap-3">
    <label class="flex min-h-11 items-center gap-2"><input v-model="policy.enabled" name="enabled" type="checkbox">{{ t('ranch.admin.policyEnabled') }}</label>
    <label class="grid gap-1">{{ t('ranch.admin.effectiveAt') }}<input v-model="policy.effectiveAt" name="effectiveAt" required placeholder="2031-01-06T00:00:00Z" class="min-h-11 rounded border bg-transparent p-2"></label>
    <label class="grid gap-1">{{ t('ranch.admin.globalWeeklyCap') }}<input v-model="policy.globalWeeklyCap" name="globalWeeklyCap" required inputmode="numeric" pattern="[1-9][0-9]{0,18}" maxlength="19" class="min-h-11 rounded border bg-transparent p-2"></label>
    <div v-for="source in policySources" :key="source.sourceType" class="grid gap-3 rounded border p-3 sm:grid-cols-3">
     <label class="flex min-h-11 items-center gap-2"><input v-model="source.enabled" :name="`${source.sourceType}.enabled`" type="checkbox">{{ t(`ranch.admin.source.${source.sourceType}`) }}</label>
     <label class="grid gap-1">{{ t('ranch.admin.amountPoints') }}<input v-model="source.amountPoints" :name="`${source.sourceType}.amountPoints`" required inputmode="numeric" pattern="[1-9][0-9]{0,18}" maxlength="19" class="min-h-11 rounded border bg-transparent p-2"></label>
     <label class="grid gap-1">{{ t('ranch.admin.countLimit') }}<input v-model="source.countLimit" :name="`${source.sourceType}.countLimit`" required inputmode="numeric" pattern="[1-9][0-9]{0,9}" maxlength="10" class="min-h-11 rounded border bg-transparent p-2"></label>
    </div>
    <label v-for="key in deliveryFields" :key="key" class="grid gap-1">{{ t(`ranch.admin.${key}`) }}<input v-model="delivery[key]" :name="key" required inputmode="numeric" pattern="[1-9][0-9]{0,9}" maxlength="10" class="min-h-11 rounded border bg-transparent p-2"></label>
    <label class="grid gap-1">{{ t('ranch.admin.reason') }}<input v-model="policy.reasonCode" name="reasonCode" required maxlength="80" class="min-h-11 rounded border bg-transparent p-2"></label>
    <Button type="submit" :disabled="!policyValid" :label="t('ranch.admin.publishPolicy')" class="min-h-11" />
   </fieldset>
  </form>
  <p role="status" aria-live="polite">{{ message }}</p>
 </section>
</template>
