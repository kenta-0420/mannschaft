<script setup lang="ts">
import { computed } from 'vue'
import type { RollCallEntry } from '~/types/care'

/**
 * F03.12 §14 主催者点呼の確定前確認ダイアログ。
 *
 * - PRESENT / LATE / ABSENT の人数集計を表示
 * - 「保護者へ即時通知」スイッチの状態をエコー表示
 * - {@code guardianSetupWarnings}（ケア対象だが見守り者未設定）を警告として表示
 * - 「確定」ボタンで親に確定を通知、「キャンセル」で閉じる
 */

const props = defineProps<{
  /** v-model:visible 用。 */
  visible: boolean
  /** 送信予定エントリ。 */
  entries: RollCallEntry[]
  /** 保護者即時通知フラグ。 */
  notifyImmediately: boolean
  /** ケア対象だが見守り者未設定のユーザー名一覧（事前算出）。 */
  guardianSetupWarnings: string[]
  /** 確定処理が走っている間 true。ダブルクリック防止に使う。 */
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm' | 'cancel'): void
}>()

/** PRESENT 件数。 */
const presentCount = computed(() => props.entries.filter((it) => it.status === 'PRESENT').length)
/** LATE 件数。 */
const lateCount = computed(() => props.entries.filter((it) => it.status === 'LATE').length)
/** ABSENT 件数。 */
const absentCount = computed(() => props.entries.filter((it) => it.status === 'ABSENT').length)

const isVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

function onConfirm(): void {
  emit('confirm')
}
function onCancel(): void {
  emit('cancel')
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    v-model:visible="isVisible"
    :header="$t('event.rollCall.confirmTitle')"
    modal
    :style="{ width: 'min(480px, 92vw)' }"
    :closable="!submitting"
    data-testid="roll-call-submit-dialog"
  >
    <!-- 内訳サマリー -->
    <div class="rc-confirm__summary">
      <div class="rc-confirm__cell rc-confirm__cell--present">
        <span class="rc-confirm__label">{{ $t('event.rollCall.statusPresent') }}</span>
        <span class="rc-confirm__count" data-testid="roll-call-present-count">
          {{ presentCount }}
        </span>
      </div>
      <div class="rc-confirm__cell rc-confirm__cell--late">
        <span class="rc-confirm__label">{{ $t('event.rollCall.statusLate') }}</span>
        <span class="rc-confirm__count" data-testid="roll-call-late-count">
          {{ lateCount }}
        </span>
      </div>
      <div class="rc-confirm__cell rc-confirm__cell--absent">
        <span class="rc-confirm__label">{{ $t('event.rollCall.statusAbsent') }}</span>
        <span class="rc-confirm__count" data-testid="roll-call-absent-count">
          {{ absentCount }}
        </span>
      </div>
    </div>

    <!-- 通知設定エコー -->
    <p class="rc-confirm__notify" data-testid="roll-call-notify-echo">
      <span v-if="notifyImmediately">
        {{ $t('event.rollCall.willNotifyGuardians') }}
      </span>
      <span v-else>
        {{ $t('event.rollCall.willNotNotifyGuardians') }}
      </span>
    </p>

    <!-- 警告 -->
    <div
      v-if="guardianSetupWarnings.length > 0"
      class="rc-confirm__warning"
      data-testid="roll-call-warnings"
    >
      <p class="rc-confirm__warning-title">
        {{ $t('event.rollCall.guardianSetupWarning') }}
      </p>
      <ul class="rc-confirm__warning-list">
        <li v-for="name in guardianSetupWarnings" :key="name">
          {{ name }}
        </li>
      </ul>
    </div>

    <template #footer>
      <Button
        :label="$t('common.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        data-testid="roll-call-submit-cancel"
        @click="onCancel"
      />
      <Button
        :label="$t('event.rollCall.confirm')"
        :loading="submitting"
        data-testid="roll-call-submit-confirm"
        @click="onConfirm"
      />
    </template>
  </Dialog>
</template>

<style scoped>
.rc-confirm__summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.rc-confirm__cell {
  border: 1px solid var(--p-content-border-color, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.5rem 0.25rem;
  text-align: center;
}
.rc-confirm__cell--present { background: rgba(34, 197, 94, 0.10); }
.rc-confirm__cell--late { background: rgba(234, 179, 8, 0.10); }
.rc-confirm__cell--absent { background: rgba(239, 68, 68, 0.10); }
.rc-confirm__label {
  display: block;
  font-size: 0.75rem;
  color: var(--p-text-muted-color, #6b7280);
}
.rc-confirm__count {
  display: block;
  font-size: 1.5rem;
  font-weight: 700;
}
.rc-confirm__notify {
  margin: 0.25rem 0 0.75rem;
  font-size: 0.875rem;
}
.rc-confirm__warning {
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.4);
  border-radius: 0.5rem;
  padding: 0.6rem 0.75rem;
}
.rc-confirm__warning-title {
  margin: 0 0 0.25rem;
  font-weight: 600;
  color: #b91c1c;
}
.rc-confirm__warning-list {
  margin: 0;
  padding-left: 1.25rem;
  font-size: 0.875rem;
}
</style>
