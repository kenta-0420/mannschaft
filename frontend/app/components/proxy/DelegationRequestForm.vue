<script setup lang="ts">
import type { MemberResponse } from '~/types/member'
import type { components } from '~/types/generated'

/**
 * F03.10 代理出席 — 代理人指定フォームコンポーネント。
 * 出欠フォームやイベント詳細内に埋め込んで使う。
 */

const props = defineProps<{
  scopeType: 'team' | 'organization'
  /** getMembers と同じURL識別子。投票APIには詳細APIの numericId を渡す。 */
  scopeId: string
  targetId: number      // scheduleId または eventId
  targetType: 'schedule' | 'event'
  disabled?: boolean
}>()

const emit = defineEmits<{
  created: []
  cancelled: []
}>()

const { t } = useI18n()
const notification = useNotification()
const scheduleDelegationApi = useScheduleDelegationApi()
const eventDelegationApi = useEventDelegationApi()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const votingApi = useVotingApi()
const { handleApiError } = useErrorHandler()

// --- 状態 ---
const submitting = ref(false)
const members = ref<MemberResponse[]>([])
const voteSessionOptions = ref<components['schemas']['SessionListResponse'][]>([])
const voteSessionsError = shallowRef<unknown>(null)
const loadingMembers = ref(false)
const loadingVoteSessions = ref(false)
const errorMessage = ref<string | null>(null)
let voteSessionsSequence = 0

const form = ref({
  delegateId: null as number | null,
  reason: '',
  includeProxyVote: false,
  proxyVoteSessionId: null as number | null,
})

// --- メンバー一覧取得 ---
async function fetchMembers() {
  loadingMembers.value = true
  try {
    if (props.scopeType === 'team') {
      const res = await teamApi.getMembers(props.scopeId, { size: 200 })
      members.value = (res as { data: MemberResponse[] }).data ?? []
    } else {
      const res = await orgApi.getMembers(props.scopeId, { size: 200 })
      members.value = (res as { data: MemberResponse[] }).data ?? []
    }
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    loadingMembers.value = false
  }
}

// --- 投票セッション一覧取得（イベントの場合のみ）---
async function fetchVoteSessions() {
  const request = ++voteSessionsSequence
  voteSessionOptions.value = []
  voteSessionsError.value = null
  form.value.includeProxyVote = false
  form.value.proxyVoteSessionId = null
  if (props.targetType !== 'event') {
    loadingVoteSessions.value = false
    return
  }
  const scopeType = props.scopeType
  const scopeSlug = props.scopeId
  loadingVoteSessions.value = true
  try {
    const scope = scopeType === 'team' ? await teamApi.getTeam(scopeSlug) : await orgApi.getOrganization(scopeSlug)
    if (request !== voteSessionsSequence) return
    const numericId = scope.data.numericId
    if (typeof numericId !== 'number' || !Number.isSafeInteger(numericId) || numericId <= 0) {
      throw new Error('Invalid voting scope identifier')
    }
    const res = await votingApi.getSessions(scopeType === 'team'
      ? { scope_type: 'TEAM', team_id: numericId, status: 'OPEN' }
      : { scope_type: 'ORGANIZATION', organization_id: numericId, status: 'OPEN' })
    if (request !== voteSessionsSequence) return
    if (!Array.isArray(res.data)) throw new Error('Invalid voting session list response')
    voteSessionOptions.value = res.data
  } catch (cause) {
    if (request !== voteSessionsSequence) return
    voteSessionsError.value = cause
    handleApiError(cause)
  } finally {
    if (request === voteSessionsSequence) loadingVoteSessions.value = false
  }
}

watch(() => [props.scopeType, props.scopeId, props.targetType, props.targetId], () => {
  void fetchVoteSessions()
}, { flush: 'sync' })
onScopeDispose(() => { ++voteSessionsSequence })

// --- includeProxyVote をオフにしたらセッション選択をクリア ---
watch(
  () => form.value.includeProxyVote,
  (val) => {
    if (!val) {
      form.value.proxyVoteSessionId = null
    }
  },
)

// --- メンバーセレクト用オプション ---
const memberOptions = computed(() =>
  members.value.map((m) => ({
    label: m.displayName,
    value: m.userId,
  })),
)

// --- 投票セッションセレクト用オプション ---
const voteSessionSelectOptions = computed(() =>
  voteSessionOptions.value.map((s) => ({
    label: s.title,
    value: s.id,
  })),
)

// --- 送信 ---
async function submit() {
  if (!form.value.delegateId) {
    notification.warn(t('proxy.delegation.errors.self'))
    return
  }

  submitting.value = true
  errorMessage.value = null

  try {
    if (props.targetType === 'schedule') {
      await scheduleDelegationApi.createDelegation(props.targetId, {
        delegateId: form.value.delegateId,
        reason: form.value.reason.trim() || undefined,
      })
    } else {
      await eventDelegationApi.createDelegation(props.targetId, {
        delegateId: form.value.delegateId,
        reason: form.value.reason.trim() || undefined,
        proxyVoteSessionId:
          form.value.includeProxyVote && form.value.proxyVoteSessionId
            ? form.value.proxyVoteSessionId
            : undefined,
      })
    }
    notification.success(t('dialog.success'))
    emit('created')
  } catch (err: unknown) {
    const status = (err as { status?: number })?.status
    if (status === 429) {
      errorMessage.value = t('proxy.delegation.errors.rate_limit')
    } else {
      const apiErr = err as { data?: { message?: string } }
      errorMessage.value = apiErr?.data?.message ?? t('dialog.error')
    }
  } finally {
    submitting.value = false
  }
}

function cancel() {
  emit('cancelled')
}

// --- マウント時にメンバーと投票セッションを取得 ---
onMounted(() => {
  fetchMembers()
  if (props.targetType === 'event') {
    fetchVoteSessions()
  }
})
</script>

<template>
  <div class="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
    <!-- 代理人選択 -->
    <div>
      <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
        {{ $t('proxy.delegation.delegate_label') }}
      </label>
      <Select
        v-model="form.delegateId"
        :options="memberOptions"
        option-label="label"
        option-value="value"
        :placeholder="$t('proxy.delegation.delegate_placeholder')"
        :loading="loadingMembers"
        :disabled="disabled || submitting"
        filter
        class="w-full"
      />
    </div>

    <!-- 委任理由 -->
    <div>
      <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
        {{ $t('proxy.delegation.reason_label') }}
      </label>
      <Textarea
        v-model="form.reason"
        :placeholder="$t('proxy.delegation.reason_placeholder')"
        :maxlength="500"
        :disabled="disabled || submitting"
        rows="3"
        class="w-full"
      />
    </div>

    <DashboardErrorState v-if="targetType === 'event' && voteSessionsError" :error="voteSessionsError" :show-retry="true" @retry="fetchVoteSessions" />

    <!-- 投票委任（イベントの場合のみ・セッションが存在する場合のみ） -->
    <template v-if="targetType === 'event' && voteSessionOptions.length > 0">
      <div class="flex items-center gap-3">
        <Checkbox
          v-model="form.includeProxyVote"
          :binary="true"
          input-id="includeProxyVote"
          :disabled="disabled || submitting"
        />
        <label for="includeProxyVote" class="text-sm text-gray-700 dark:text-gray-300">
          {{ $t('proxy.delegation.proxy_vote_label') }}
        </label>
      </div>

      <div v-if="form.includeProxyVote" class="ml-6">
        <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {{ $t('proxy.delegation.proxy_vote_session') }}
        </label>
        <Select
          v-model="form.proxyVoteSessionId"
          :options="voteSessionSelectOptions"
          option-label="label"
          option-value="value"
          :placeholder="$t('proxy.delegation.proxy_vote_session_placeholder')"
          :loading="loadingVoteSessions"
          :disabled="disabled || submitting"
          class="w-full"
        />
      </div>
    </template>

    <!-- エラーメッセージ -->
    <p v-if="errorMessage" class="text-sm text-red-600 dark:text-red-400">
      {{ errorMessage }}
    </p>

    <!-- ボタン -->
    <div class="flex justify-end gap-2">
      <Button
        :label="$t('button.cancel')"
        text
        :disabled="submitting"
        @click="cancel"
      />
      <Button
        :label="$t('button.save')"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="disabled || !form.delegateId"
        @click="submit"
      />
    </div>
  </div>
</template>
