<script setup lang="ts">
import type { LoyaltyAwardRequest, LoyaltyPage, LoyaltyTransaction } from '~/types/team-loyalty'
const props = defineProps<{ teamId: number }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const api = useTeamLoyaltyApi()
const { handleApiError } = useErrorHandler()
const status = ref<'PENDING' | 'ALL'>('PENDING')
const page = ref<LoyaltyPage<LoyaltyAwardRequest> | null>(null)
const selected = ref<LoyaltyAwardRequest | null>(null)
const code = ref('')
const loading = ref(true)
const error = shallowRef<unknown>(null)
const action = ref<'approve' | 'reject' | null>(null)
const mutation = useLoyaltyMutation<LoyaltyTransaction | LoyaltyAwardRequest>()
async function load(cursor?: string | null) {
  loading.value = true
  error.value = null
  try {
    page.value = await api.requests(props.teamId, status.value, cursor)
    if (selected.value) selected.value = await api.request(props.teamId, selected.value.id)
  } catch (e) {
    error.value = e
    handleApiError(e, 'team-loyalty')
  } finally {
    loading.value = false
  }
}
async function select(request: LoyaltyAwardRequest) {
  if (mutation.uncertain.value || mutation.pending.value) return
  try {
    selected.value = await api.request(props.teamId, request.id)
    code.value = ''
  } catch (e) {
    handleApiError(e, 'team-loyalty')
  }
}
async function act(kind: 'approve' | 'reject') {
  if (!selected.value) return
  try {
    if (mutation.uncertain.value) await mutation.retry()
    else {
      action.value = kind
      const id = selected.value.id
      const body = { expectedRequestVersion: selected.value.version }
      if (kind === 'approve')
        await mutation.start(
          { ...body, verificationCode: code.value.trim().toUpperCase() },
          (snapshot, key) => api.approve(props.teamId, id, snapshot, key),
        )
      else
        await mutation.start(body, (snapshot, key) => api.reject(props.teamId, id, snapshot, key))
    }
    await load()
    emit('changed')
  } catch (e) {
    handleApiError(e, 'team-loyalty')
    if (!mutation.uncertain.value) await load()
  }
}
onMounted(() => load())
</script>
<template>
  <SectionCard :title="t('loyalty.requests.title')">
    <div class="mb-4 flex flex-wrap gap-3">
      <Select
        v-model="status"
        :options="[
          { label: t('loyalty.requests.status.pending'), value: 'PENDING' },
          { label: t('loyalty.requests.all'), value: 'ALL' },
        ]"
        option-label="label"
        option-value="value"
        :disabled="mutation.uncertain.value || mutation.pending.value"
        :aria-label="t('loyalty.requests.title')"
        @change="load()"
      />
      <Button class="min-h-11" :label="t('loyalty.refresh')" @click="load()" />
    </div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load()" />
    <template v-else-if="page">
      <DashboardEmptyState
        v-if="!page.data.length && !page.meta.hasNext"
        :message="t('loyalty.requests.empty')"
      />
      <ul>
        <li v-for="item in page.data" :key="item.id">
          <Button
            class="min-h-11 w-full"
            severity="secondary"
            :label="`${item.applicantDisplayName} · ${item.ruleName} · ${t(`loyalty.requests.status.${item.status.toLowerCase()}`)}`"
            @click="select(item)"
          />
        </li>
      </ul>
      <Button
        v-if="page.meta.hasNext"
        class="min-h-11"
        :label="t(page.data.length ? 'loyalty.more' : 'loyalty.requests.moreCandidates')"
        @click="load(page.meta.nextCursor)"
      />
      <LoyaltyRequestDetail v-if="selected" :request="selected" class="mt-4">
        <p>{{ t('loyalty.requests.compareBeforeApprove') }}</p>
        <label for="loyalty-verification-code" class="block">{{
          t('loyalty.requests.enterCode')
        }}</label>
        <InputText
          id="loyalty-verification-code"
          v-model="code"
          maxlength="8"
          autocomplete="off"
          class="w-full text-base"
          :disabled="mutation.uncertain.value || mutation.pending.value"
        />
        <p v-if="!selected.canApprove && selected.status === 'PENDING'">
          {{ t('loyalty.requests.selfApprovalForbidden') }}
        </p>
        <p v-if="mutation.uncertain.value" role="alert">{{ t('loyalty.error.uncertain') }}</p>
        <Button
          v-if="mutation.uncertain.value && action"
          class="mt-3 min-h-11"
          :label="t('loyalty.retrySame')"
          :loading="mutation.pending.value"
          @click="act(action)"
        />
        <div v-else-if="selected.status === 'PENDING'" class="mt-3 flex flex-wrap gap-3">
          <Button
            class="min-h-11"
            :label="t('loyalty.requests.approve')"
            :disabled="!selected.canApprove || !/^[A-Z2-7]{8}$/.test(code.trim().toUpperCase())"
            :loading="mutation.pending.value"
            @click="act('approve')"
          />
          <Button
            class="min-h-11"
            :label="t('loyalty.requests.reject')"
            severity="danger"
            :loading="mutation.pending.value"
            @click="act('reject')"
          />
        </div>
      </LoyaltyRequestDetail>
    </template>
  </SectionCard>
</template>
