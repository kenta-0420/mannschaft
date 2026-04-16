<script setup lang="ts">
/**
 * F01.5 他チームフォロー実行ボタン。
 *
 * 役割:
 * - {@link useFriendTeamsApi.follow} を呼び出し、フォローリクエストを送信する。
 * - NOWAIT 競合（202 Accepted + retryAfterSeconds）を検知した場合、
 *   カウントダウン表示 → 自動再試行を最大3回まで行う。
 * - 3回失敗時はエラートーストを表示し、親にリトライスケジュール終了を通知する。
 *
 * Props:
 *   teamId       — 自チーム ID
 *   targetTeamId — フォロー先チーム ID
 *   comment      — 任意の挨拶コメント（最大300文字）
 *   disabled     — ボタンを無効化するか
 *
 * Emits:
 *   followed        — フォロー成功時（FollowTeamResponse を渡す）
 *   retryScheduled  — NOWAIT でリトライ予約された時（残秒数）
 */
import type { FollowTeamResponse } from '~/types/friends'

const props = defineProps<{
  teamId: number
  targetTeamId: number
  comment?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  followed: [response: FollowTeamResponse]
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
let retryTimer: ReturnType<typeof setInterval> | null = null

function clearRetryTimer() {
  if (retryTimer) {
    clearInterval(retryTimer)
    retryTimer = null
  }
}

async function execute() {
  submitting.value = true
  try {
    const response = await follow(props.teamId, {
      targetTeamId: props.targetTeamId,
      comment: props.comment,
    })

    // NOWAIT 競合 (followId=null + retryAfterSeconds) の場合
    if (response.followId === null && response.retryAfterSeconds && response.retryAfterSeconds > 0) {
      if (retryCount.value >= MAX_RETRY) {
        notification.error(t('dialog.error'), t('friends.messages.follow_retry', { seconds: 0 }))
        retryCount.value = 0
        submitting.value = false
        return
      }
      retryCount.value += 1
      retryCountdown.value = response.retryAfterSeconds
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
    handleApiError(error)
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
      void execute()
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

onBeforeUnmount(() => {
  clearRetryTimer()
})
</script>

<template>
  <Button
    :label="buttonLabel"
    :loading="submitting"
    :disabled="disabled || isBusy"
    icon="pi pi-user-plus"
    @click="execute"
  />
</template>
