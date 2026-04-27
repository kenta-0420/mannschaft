<script setup lang="ts">
import { computed } from 'vue'
import type {
  AbsenceReason,
  RollCallCandidate,
  RollCallEntry,
  RollCallStatus,
} from '~/types/care'

/**
 * F03.12 §14 主催者点呼の 1 名分の行コンポーネント。
 *
 * - PRESENT / LATE / ABSENT のセグメントを大きく表示しスマホ片手で操作できるようにする
 * - LATE 選択時は遅刻分数（1〜120）を InputNumber でインライン入力
 * - ABSENT 選択時は理由（SICK/PERSONAL_REASON/NOT_ARRIVED/OTHER）を Select
 * - 既にチェックイン済みのケースはバッジで明示
 * - ケア対象者は専用バッジ + 見守り者数を表示し、未設定（watcherCount=0）は警告色に
 */

const props = defineProps<{
  candidate: RollCallCandidate
  modelValue: RollCallEntry | undefined
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: RollCallEntry): void
}>()

/** 現在のステータス（未選択時は undefined）。 */
const currentStatus = computed<RollCallStatus | undefined>(() => props.modelValue?.status)

/** ABSENT 理由の選択肢。 */
const absenceReasonOptions: { label: string; value: AbsenceReason }[] = [
  { label: '体調不良', value: 'SICK' },
  { label: '個人都合', value: 'PERSONAL_REASON' },
  { label: '未到着・連絡なし', value: 'NOT_ARRIVED' },
  { label: 'その他', value: 'OTHER' },
]

/**
 * ステータス切替時に補助項目をリセットして emit する。
 *
 * - PRESENT / ABSENT へ → lateArrivalMinutes をクリア
 * - PRESENT / LATE  へ → absenceReason をクリア
 */
function selectStatus(status: RollCallStatus): void {
  const next: RollCallEntry = {
    userId: props.candidate.userId,
    status,
  }
  if (status === 'LATE') {
    // LATE は分数の初期値を 5 にしておく（最頻値想定）
    next.lateArrivalMinutes = props.modelValue?.lateArrivalMinutes ?? 5
  } else if (status === 'ABSENT') {
    next.absenceReason = props.modelValue?.absenceReason ?? 'NOT_ARRIVED'
  }
  emit('update:modelValue', next)
}

/** 遅刻分数を更新する。 */
function updateLateMinutes(minutes: number | null | undefined): void {
  if (props.modelValue?.status !== 'LATE') return
  emit('update:modelValue', {
    ...props.modelValue,
    lateArrivalMinutes: minutes ?? 1,
  })
}

/** 欠席理由を更新する。 */
function updateAbsenceReason(reason: AbsenceReason): void {
  if (props.modelValue?.status !== 'ABSENT') return
  emit('update:modelValue', {
    ...props.modelValue,
    absenceReason: reason,
  })
}

/** 見守り者未設定で警告対象か。 */
const isGuardianMissingWarn = computed(
  () => props.candidate.isUnderCare && props.candidate.watcherCount === 0,
)
</script>

<template>
  <div
    :data-testid="`roll-call-row-${candidate.userId}`"
    :data-status="currentStatus ?? 'UNCHECKED'"
    class="rc-row"
  >
    <!-- 左: アバター + 名前 + バッジ -->
    <div class="rc-row__profile">
      <img
        v-if="candidate.avatarUrl"
        :src="candidate.avatarUrl"
        :alt="candidate.displayName"
        class="rc-row__avatar"
      >
      <div v-else class="rc-row__avatar rc-row__avatar--placeholder" aria-hidden="true">
        {{ candidate.displayName.charAt(0) }}
      </div>
      <div class="rc-row__meta">
        <div class="rc-row__name">
          {{ candidate.displayName }}
        </div>
        <div class="rc-row__badges">
          <span v-if="candidate.isAlreadyCheckedIn" class="rc-badge rc-badge--checked">
            ✓ {{ $t('event.rollCall.alreadyCheckedIn') }}
          </span>
          <span
            v-if="candidate.isUnderCare"
            class="rc-badge rc-badge--care"
            :title="$t('event.rollCall.underCare')"
          >
            👶
          </span>
          <span
            v-if="candidate.isUnderCare"
            class="rc-badge"
            :class="isGuardianMissingWarn ? 'rc-badge--warn' : 'rc-badge--neutral'"
          >
            👤×{{ candidate.watcherCount }}
          </span>
          <span v-if="candidate.rsvpStatus" class="rc-badge rc-badge--rsvp">
            {{ candidate.rsvpStatus }}
          </span>
        </div>
      </div>
    </div>

    <!-- 中央: ステータスセグメント -->
    <div class="rc-row__seg" role="group" :aria-label="$t('event.rollCall.statusGroup')">
      <button
        type="button"
        class="rc-seg-btn rc-seg-btn--present"
        :class="{ 'rc-seg-btn--active': currentStatus === 'PRESENT' }"
        :data-testid="`roll-call-row-${candidate.userId}-present`"
        @click="selectStatus('PRESENT')"
      >
        {{ $t('event.rollCall.statusPresent') }}
      </button>
      <button
        type="button"
        class="rc-seg-btn rc-seg-btn--late"
        :class="{ 'rc-seg-btn--active': currentStatus === 'LATE' }"
        :data-testid="`roll-call-row-${candidate.userId}-late`"
        @click="selectStatus('LATE')"
      >
        {{ $t('event.rollCall.statusLate') }}
      </button>
      <button
        type="button"
        class="rc-seg-btn rc-seg-btn--absent"
        :class="{ 'rc-seg-btn--active': currentStatus === 'ABSENT' }"
        :data-testid="`roll-call-row-${candidate.userId}-absent`"
        @click="selectStatus('ABSENT')"
      >
        {{ $t('event.rollCall.statusAbsent') }}
      </button>
    </div>

    <!-- 右: 補助入力 -->
    <div class="rc-row__detail">
      <div v-if="currentStatus === 'LATE'" class="rc-row__late">
        <label class="rc-row__detail-label">
          {{ $t('event.rollCall.lateMinutes') }}
        </label>
        <InputNumber
          :model-value="modelValue?.lateArrivalMinutes ?? 1"
          :min="1"
          :max="120"
          show-buttons
          button-layout="horizontal"
          :input-style="{ width: '4rem', textAlign: 'center' }"
          :data-testid="`roll-call-row-${candidate.userId}-late-minutes`"
          @update:model-value="updateLateMinutes"
        />
      </div>
      <div v-else-if="currentStatus === 'ABSENT'" class="rc-row__absent">
        <label class="rc-row__detail-label">
          {{ $t('event.rollCall.absenceReason') }}
        </label>
        <Select
          :model-value="modelValue?.absenceReason ?? 'NOT_ARRIVED'"
          :options="absenceReasonOptions"
          option-label="label"
          option-value="value"
          :data-testid="`roll-call-row-${candidate.userId}-absent-reason`"
          @update:model-value="updateAbsenceReason"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.rc-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto minmax(140px, 200px);
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
  background: var(--p-content-background, #fff);
}

.rc-row[data-status='PRESENT'] {
  background: rgba(34, 197, 94, 0.08);
}
.rc-row[data-status='LATE'] {
  background: rgba(234, 179, 8, 0.08);
}
.rc-row[data-status='ABSENT'] {
  background: rgba(239, 68, 68, 0.08);
}

.rc-row__profile {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: 0;
}

.rc-row__avatar {
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 9999px;
  object-fit: cover;
}
.rc-row__avatar--placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--p-surface-200, #e5e7eb);
  color: var(--p-text-color, #1f2937);
  font-weight: 600;
}

.rc-row__meta {
  min-width: 0;
}
.rc-row__name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rc-row__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-top: 0.2rem;
  font-size: 0.75rem;
}

.rc-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.1rem 0.45rem;
  border-radius: 9999px;
  background: var(--p-surface-100, #f3f4f6);
  color: var(--p-text-muted-color, #6b7280);
}
.rc-badge--checked { background: rgba(34, 197, 94, 0.15); color: #15803d; }
.rc-badge--care { background: rgba(59, 130, 246, 0.15); color: #1d4ed8; }
.rc-badge--warn { background: rgba(239, 68, 68, 0.15); color: #b91c1c; font-weight: 600; }
.rc-badge--rsvp { background: rgba(99, 102, 241, 0.12); color: #4338ca; }

.rc-row__seg {
  display: flex;
  gap: 0.25rem;
}
.rc-seg-btn {
  min-width: 4rem;
  min-height: 2.5rem;
  border: 1px solid var(--p-form-field-border-color, #d1d5db);
  border-radius: 0.5rem;
  background: var(--p-content-background, #fff);
  color: var(--p-text-color, #1f2937);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.1s ease, color 0.1s ease;
}
.rc-seg-btn:hover {
  background: var(--p-surface-100, #f3f4f6);
}
.rc-seg-btn--active.rc-seg-btn--present {
  background: #16a34a;
  border-color: #16a34a;
  color: #fff;
}
.rc-seg-btn--active.rc-seg-btn--late {
  background: #ca8a04;
  border-color: #ca8a04;
  color: #fff;
}
.rc-seg-btn--active.rc-seg-btn--absent {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}

.rc-row__detail {
  min-width: 0;
}
.rc-row__detail-label {
  display: block;
  font-size: 0.7rem;
  color: var(--p-text-muted-color, #6b7280);
  margin-bottom: 0.15rem;
}
.rc-row__late, .rc-row__absent {
  display: flex;
  flex-direction: column;
}
</style>
