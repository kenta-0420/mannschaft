<script setup lang="ts">
/**
 * F01.5 他チームフォロー実行ボタン。
 *
 * 役割:
 * - {@link useFriendTeamsApi.follow} を呼び出し、フォローリクエストを送信する。
 * - NOWAIT 競合（202 Accepted + retryAfterSeconds）を検知した場合、
 *   カウントダウン表示 → 自動再試行を最大3回まで行う。
 * - 3回の再送後も未確定なら情報を表示し、手動の一覧再取得を提供する。
 *
 * Props:
 *   teamId         — 申請元チーム数値ID（既存slug呼出しにも互換）
 *   targetTeamSlug — フォロー先チームの slug（URL 名。数値 ID は利用者が知り得ないため使わない）
 *   comment        — 任意の挨拶コメント（最大300文字）
 *   disabled       — ボタンを無効化するか
 *
 * Emits:
 *   followed        — フォロー成功時（FollowTeamResponse を渡す）
 *   retryScheduled  — NOWAIT でリトライ予約された時（残秒数）
 *   alreadyFollowing — 409 SOCIAL_102時（情報toast後の一覧再取得要求）
 */
import type { FollowTeamResponse } from '~/types/friends'

defineOptions({ inheritAttrs: false })
const attrs = useAttrs()
const props = defineProps<{
  teamId: string
  targetTeamSlug: string
  comment?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  busy: [value: boolean]
  forbidden: []
  refresh: []
  followed: [response: FollowTeamResponse]
  alreadyFollowing: []
  retryScheduled: [seconds: number]
}>()

const { t } = useI18n()
const { follow } = useFriendTeamsApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const MAX_RETRY = 3
const submitting = ref(false)
const retryCount = ref(0)
const retryCountdown = ref(0)
let lockedRequest: { teamId: string; targetTeamSlug: string; comment?: string } | null = null
const waiting = ref(false)
let disposed = false
let retryTimer: ReturnType<typeof setInterval> | null = null

function clearRetryTimer() {
  if (retryTimer) {
    clearInterval(retryTimer)
    retryTimer = null
  }
}

async function execute(retrying = false) {
  // 再入ガード: :disabled/:loading は再描画後にしか効かないため、同一 tick の連打はここで弾く
  if (disposed || submitting.value || (!retrying && (retryCountdown.value > 0 || props.disabled))) return
  if (!retrying) lockedRequest = { teamId: props.teamId, targetTeamSlug: props.targetTeamSlug, comment: props.comment }
  if (!lockedRequest) return
  waiting.value = false
  submitting.value = true
  try {
    const response = await follow(lockedRequest.teamId, {
      targetTeamSlug: lockedRequest.targetTeamSlug,
      comment: lockedRequest.comment,
    })

    if (disposed) return

    // followIdが発行済みでも、retryAfterSecondsがある応答は未確定。
    if (response.retryAfterSeconds != null) {
      emit('refresh')
      if (retryCount.value >= MAX_RETRY) {
        waiting.value = true
        notification.info(t('friends.request.pending'))
        retryCount.value = 0
        submitting.value = false
        return
      }
      retryCount.value += 1
      retryCountdown.value = Math.max(1, response.retryAfterSeconds)
      emit('retryScheduled', retryCountdown.value)
      startCountdown()
      return
    }

    // 成功: リトライ状態をリセット
    retryCount.value = 0
    retryCountdown.value = 0
    clearRetryTimer()
    if (response.mutual) {
      notification.success(t('friends.messages.follow_mutual_success'))
    }
    else {
      notification.success(t('friends.messages.follow_success'))
    }
    emit('followed', response)
  }
  catch (error) {
    if (disposed) return
    const apiError = error as { statusCode?: number; data?: { error?: { code?: string } } }
    if (apiError.statusCode === 409 && apiError.data?.error?.code === 'SOCIAL_102') {
      notification.info(t('friends.messages.already_following'))
      emit('alreadyFollowing')
    }
    else if (apiError.statusCode === 403) {
      handleApiError(error)
      emit('forbidden')
    }
    else if (apiError.statusCode === 404 && apiError.data?.error?.code === 'SOCIAL_104') {
      notification.error(t('friends.errors.target_unavailable'))
    }
    else {
      handleApiError(error)
    }
    retryCount.value = 0
    clearRetryTimer()
  }
  finally {
    submitting.value = false
  }
}

function startCountdown() {
  clearRetryTimer()
  retryTimer = setInterval(() => {
    retryCountdown.value -= 1
    if (retryCountdown.value <= 0) {
      clearRetryTimer()
      // 自動再試行
      void execute(true)
    }
  }, 1000)
}

const buttonLabel = computed(() => {
  if (retryCountdown.value > 0) {
    return t('friends.messages.follow_retry', { seconds: retryCountdown.value })
  }
  return t('friends.actions.follow')
})

const isBusy = computed(() => submitting.value || retryCountdown.value > 0)
watch(isBusy, value => emit('busy', value), { flush: 'sync' })

onBeforeUnmount(() => {
  disposed = true
  clearRetryTimer()
})
</script>

<template>
  <span class="inline-flex flex-wrap items-center gap-2">
  <Button
    v-bind="attrs"
    :label="buttonLabel"
    :loading="submitting"
    :disabled="disabled || isBusy"
    icon="pi pi-user-plus"
    @click="execute()"
  />
  <Button v-if="waiting" :label="t('friends.request.refresh')" icon="pi pi-refresh" class="min-h-11" data-testid="friend-request-manual-refresh" @click="emit('refresh')" />
  </span>
</template>
