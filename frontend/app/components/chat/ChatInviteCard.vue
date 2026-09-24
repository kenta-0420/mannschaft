<script setup lang="ts">
import type { ChatInviteData } from '~/types/chat'

/**
 * 承諾型招待カード（F04.12）。
 *
 * DM のメッセージストリーム内に差し込まれる招待カード UI。
 * scopeName・状態バッジ・アクションボタンを描画する。
 *
 * - 「参加する」/「辞退する」ボタンは {@link ChatInviteData.isTarget} が true（宛先本人）のときだけ活性。
 *   発行者側（isTarget=false）には「承諾待ち」表示のみ（ボタン非表示）。
 * - status（PENDING/JOINED/EXPIRED/REVOKED）に応じて表示を出し分ける（設計書 §5）。
 */
const props = defineProps<{
  invite: ChatInviteData
  /** 承諾/辞退 API 実行中フラグ（多重送信防止・親から供給） */
  submitting?: boolean
}>()

const emit = defineEmits<{
  join: [token: string]
  decline: [token: string]
}>()

const { t } = useI18n()
const { relativeTime } = useRelativeTime()

/** PENDING かつ宛先本人 = 参加/辞退ボタンを活性化できる状態。 */
const canAct = computed(() => props.invite.status === 'PENDING' && props.invite.isTarget)

// token は宛先本人にのみ返る（非宛先は null・多層防御）。canAct 下でのみボタンが描画されるため
// 実際は常に非 null だが、型の嘘を避けるため emit 前に null を弾く（string へ絞り込み）。
function onJoin() {
  if (props.invite.token) emit('join', props.invite.token)
}
function onDecline() {
  if (props.invite.token) emit('decline', props.invite.token)
}

/** カード見出し（宛先本人は「招待が届いています」、発行者は「承諾待ち」）。 */
const heading = computed(() =>
  props.invite.isTarget
    ? t('chat.invite.card.pending', { scope: props.invite.scopeName })
    : t('chat.invite.card.waiting'),
)

/** 状態バッジの表示ラベルと配色。 */
const statusBadge = computed<{ label: string; classes: string }>(() => {
  switch (props.invite.status) {
    case 'JOINED':
      return {
        label: t('chat.invite.card.joined'),
        classes: 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300',
      }
    case 'EXPIRED':
      return {
        label: t('chat.invite.card.expired'),
        classes: 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-300',
      }
    case 'REVOKED':
      // inviteData は取消/辞退の理由（audit）を持たないため、視点で表示を出し分ける
      // （宛先本人視点=辞退済み / 発行者視点=取消済み・設計書 §5）。
      return {
        label: props.invite.isTarget
          ? t('chat.invite.card.declined')
          : t('chat.invite.card.revoked'),
        classes: 'bg-surface-100 text-surface-500 dark:bg-surface-700 dark:text-surface-300',
      }
    case 'PENDING':
    default:
      return {
        label: t('chat.invite.card.statusPending'),
        classes: 'bg-primary/10 text-primary',
      }
  }
})
</script>

<template>
  <div
    class="my-1 max-w-sm rounded-lg border border-surface-300 dark:border-surface-600 bg-surface-50 dark:bg-surface-800 p-3"
    data-testid="chat-invite-card"
  >
    <div class="flex items-start gap-3">
      <span
        class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
        aria-hidden="true"
      >
        <i :class="invite.scopeType === 'ORGANIZATION' ? 'pi pi-building' : 'pi pi-users'" />
      </span>
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <p class="min-w-0 flex-1 text-sm font-semibold">{{ heading }}</p>
          <span
            class="shrink-0 rounded-full px-2 py-0.5 text-xs font-medium"
            :class="statusBadge.classes"
            data-testid="chat-invite-status"
          >
            {{ statusBadge.label }}
          </span>
        </div>
        <p class="mt-0.5 truncate text-sm text-surface-600 dark:text-surface-300">
          {{ invite.scopeName }}
        </p>
        <p
          v-if="invite.status === 'PENDING'"
          class="mt-0.5 text-xs text-surface-400"
        >
          {{ $t('chat.invite.card.expiresAt', { time: relativeTime(invite.expiresAt) }) }}
        </p>

        <!-- アクション: PENDING かつ宛先本人のみ参加/辞退ボタン活性 -->
        <div v-if="canAct" class="mt-2 flex gap-2">
          <Button
            :label="$t('chat.invite.card.join')"
            size="small"
            :loading="submitting"
            :disabled="submitting"
            data-testid="chat-invite-join"
            @click="onJoin"
          />
          <Button
            :label="$t('chat.invite.card.decline')"
            size="small"
            text
            severity="secondary"
            :disabled="submitting"
            data-testid="chat-invite-decline"
            @click="onDecline"
          />
        </div>

        <!-- PENDING だが発行者側: 承諾待ちの補足表示（ボタンは出さない） -->
        <p
          v-else-if="invite.status === 'PENDING' && !invite.isTarget"
          class="mt-2 text-xs text-surface-400"
        >
          {{ $t('chat.invite.card.waiting') }}
        </p>
      </div>
    </div>
  </div>
</template>
