<script setup lang="ts">
import { computed, ref } from 'vue'
import type {
  AdvanceNoticeResponse,
  RollCallCandidate,
  RollCallEntry,
} from '~/types/care'
import RollCallEntryRow from '~/components/event/rollCall/RollCallEntryRow.vue'
import RollCallSubmitDialog from '~/components/event/rollCall/RollCallSubmitDialog.vue'

/**
 * F03.12 §14 主催者点呼シート。
 *
 * <p>候補者一覧に対して PRESENT/LATE/ABSENT を素早く付与し、
 * 「点呼を確定」ボタンで一括送信する画面の中核。少年団コーチが
 * フィールド片手で操作することを想定し、検索・フィルタ・一括ボタンを
 * 上部に固定配置する。</p>
 *
 * <h3>役割分担</h3>
 * <ul>
 *   <li>候補者の取得・送信は親（ページ）側の {@code useRollCall} が担当</li>
 *   <li>本コンポーネントは UI だけを保持し、確定時に
 *       {@code submit} イベントで {@code entries} と {@code notifyImmediately} を上に投げる</li>
 * </ul>
 */

const props = defineProps<{
  teamId: number
  eventId: number
  candidates: RollCallCandidate[]
  /** 事前連絡サマリーバナー用（任意。空配列なら非表示）。 */
  advanceNotices?: AdvanceNoticeResponse[]
  /** 送信中表示用。 */
  submitting?: boolean
}>()

const emit = defineEmits<{
  (
    e: 'submit',
    payload: { entries: RollCallEntry[]; notifyImmediately: boolean },
  ): void
  (e: 'open-history'): void
}>()

// ============================================================
// 内部 state
// ============================================================

/** userId → entry の状態マップ。 */
const entriesMap = ref(new Map<number, RollCallEntry>())

/** 検索文字列（表示名の部分一致）。 */
const searchQuery = ref('')

/** 「未チェック者のみ」フィルタ。 */
const filterMode = ref<'all' | 'unchecked'>('all')

/** RSVP フィルタ。 */
const rsvpFilter = ref<'all' | 'attending' | 'maybe' | 'no_response'>('all')

/** 保護者へ即時通知。デフォルト ON（少年団主催者の運用に合わせる）。 */
const notifyImmediately = ref(true)

const showConfirmDialog = ref(false)

// ============================================================
// computed: フィルタ適用後の表示一覧
// ============================================================

const filteredCandidates = computed<RollCallCandidate[]>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return props.candidates.filter((c) => {
    if (q && !c.displayName.toLowerCase().includes(q)) return false
    if (filterMode.value === 'unchecked' && entriesMap.value.has(c.userId)) {
      return false
    }
    if (rsvpFilter.value !== 'all') {
      const status = c.rsvpStatus
      if (rsvpFilter.value === 'attending' && status !== 'ATTENDING') return false
      if (rsvpFilter.value === 'maybe' && status !== 'MAYBE') return false
      if (rsvpFilter.value === 'no_response' && status !== 'NO_RESPONSE' && status !== null) return false
    }
    return true
  })
})

// ============================================================
// computed: 内訳・警告
// ============================================================

const checkedCount = computed(() => entriesMap.value.size)
const totalCount = computed(() => props.candidates.length)
const remainingCount = computed(() => totalCount.value - checkedCount.value)

/** 事前連絡サマリ（遅刻 N 名 / 欠席 M 名）。 */
const advanceLateCount = computed(
  () => (props.advanceNotices ?? []).filter((n) => n.noticeType === 'LATE').length,
)
const advanceAbsenceCount = computed(
  () => (props.advanceNotices ?? []).filter((n) => n.noticeType === 'ABSENCE').length,
)
const hasAdvanceNotices = computed(
  () => advanceLateCount.value > 0 || advanceAbsenceCount.value > 0,
)

/**
 * ケア対象者で見守り者未設定（watcherCount=0）の表示名一覧。
 *
 * <p>確定時に PRESENT に該当するユーザーだけを警告対象にする。
 * （欠席者は通知が走らないので警告不要）</p>
 */
const guardianSetupWarnings = computed<string[]>(() => {
  return props.candidates
    .filter((c) => {
      if (!c.isUnderCare || c.watcherCount > 0) return false
      const entry = entriesMap.value.get(c.userId)
      return entry?.status === 'PRESENT'
    })
    .map((c) => c.displayName)
})

const entriesArray = computed<RollCallEntry[]>(() => Array.from(entriesMap.value.values()))

// ============================================================
// 操作
// ============================================================

/** 行のステータス更新を反映する。 */
function onRowUpdate(value: RollCallEntry): void {
  const next = new Map(entriesMap.value)
  next.set(value.userId, value)
  entriesMap.value = next
}

/** 表示中の全員を PRESENT にする一括ボタン。 */
function markAllVisiblePresent(): void {
  const next = new Map(entriesMap.value)
  for (const c of filteredCandidates.value) {
    next.set(c.userId, { userId: c.userId, status: 'PRESENT' })
  }
  entriesMap.value = next
}

/** チェックを全クリア。 */
function clearAll(): void {
  entriesMap.value = new Map()
}

function openConfirm(): void {
  showConfirmDialog.value = true
}

function onConfirmDialogConfirm(): void {
  emit('submit', {
    entries: entriesArray.value,
    notifyImmediately: notifyImmediately.value,
  })
  showConfirmDialog.value = false
}

function onCancel(): void {
  showConfirmDialog.value = false
}
</script>

<template>
  <div class="rc-sheet" data-testid="roll-call-sheet">
    <!-- 上部: 事前連絡サマリーバナー -->
    <div
      v-if="hasAdvanceNotices"
      class="rc-sheet__advance-banner"
      data-testid="roll-call-advance-banner"
    >
      <span class="pi pi-info-circle" aria-hidden="true" />
      <span>
        {{
          $t('event.rollCall.advanceNoticeSummary', {
            late: advanceLateCount,
            absence: advanceAbsenceCount,
          })
        }}
      </span>
    </div>

    <!-- 上部操作バー -->
    <div class="rc-sheet__toolbar">
      <div class="rc-sheet__search">
        <span class="p-input-icon-left">
          <InputText
            v-model="searchQuery"
            :placeholder="$t('event.rollCall.searchPlaceholder')"
            data-testid="roll-call-search"
          />
        </span>
      </div>

      <div class="rc-sheet__filter">
        <SelectButton
          v-model="filterMode"
          :options="[
            { label: $t('event.rollCall.filterAll'), value: 'all' },
            { label: $t('event.rollCall.filterUnchecked'), value: 'unchecked' },
          ]"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          data-testid="roll-call-filter-mode"
        />
      </div>

      <div class="rc-sheet__count">
        <span data-testid="roll-call-progress">
          {{ $t('event.rollCall.progress', { checked: checkedCount, total: totalCount }) }}
        </span>
        <span v-if="remainingCount > 0" class="rc-sheet__count-warn">
          ({{ $t('event.rollCall.remaining', { remaining: remainingCount }) }})
        </span>
      </div>
    </div>

    <!-- 一括操作・通知設定 -->
    <div class="rc-sheet__bulk">
      <Button
        :label="$t('event.rollCall.bulkPresent')"
        icon="pi pi-check"
        outlined
        size="small"
        data-testid="roll-call-bulk-present"
        @click="markAllVisiblePresent"
      />
      <Button
        :label="$t('event.rollCall.bulkClear')"
        icon="pi pi-times"
        outlined
        severity="secondary"
        size="small"
        data-testid="roll-call-bulk-clear"
        @click="clearAll"
      />
      <div class="rc-sheet__notify-toggle">
        <ToggleSwitch
          v-model="notifyImmediately"
          input-id="roll-call-notify-toggle"
          data-testid="roll-call-notify-toggle"
        />
        <label for="roll-call-notify-toggle">
          {{ $t('event.rollCall.notifyGuardiansImmediately') }}
        </label>
      </div>
    </div>

    <!-- 行リスト本体 -->
    <div class="rc-sheet__list" data-testid="roll-call-list">
      <RollCallEntryRow
        v-for="c in filteredCandidates"
        :key="c.userId"
        :candidate="c"
        :model-value="entriesMap.get(c.userId)"
        @update:model-value="onRowUpdate"
      />
      <p
        v-if="filteredCandidates.length === 0"
        class="rc-sheet__empty"
        data-testid="roll-call-empty"
      >
        {{ $t('event.rollCall.noCandidates') }}
      </p>
    </div>

    <!-- フッター: 確定ボタン -->
    <div class="rc-sheet__footer">
      <Button
        :label="$t('event.rollCall.history')"
        icon="pi pi-history"
        text
        size="small"
        data-testid="roll-call-open-history"
        @click="emit('open-history')"
      />
      <Button
        :label="$t('event.rollCall.confirm')"
        :disabled="checkedCount === 0 || submitting"
        :loading="submitting"
        size="large"
        data-testid="roll-call-open-submit"
        @click="openConfirm"
      />
    </div>

    <RollCallSubmitDialog
      v-model:visible="showConfirmDialog"
      :entries="entriesArray"
      :notify-immediately="notifyImmediately"
      :guardian-setup-warnings="guardianSetupWarnings"
      :submitting="submitting"
      @confirm="onConfirmDialogConfirm"
      @cancel="onCancel"
    />
  </div>
</template>

<style scoped>
.rc-sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--p-content-background, #fff);
}

.rc-sheet__advance-banner {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  background: rgba(59, 130, 246, 0.08);
  border-bottom: 1px solid rgba(59, 130, 246, 0.2);
  padding: 0.5rem 1rem;
  font-size: 0.875rem;
  color: #1d4ed8;
}

.rc-sheet__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  padding: 0.6rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
}
.rc-sheet__search {
  flex: 1 1 200px;
  min-width: 0;
}
.rc-sheet__search :deep(input) {
  width: 100%;
}
.rc-sheet__filter {
  flex: 0 0 auto;
}
.rc-sheet__count {
  margin-left: auto;
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
}
.rc-sheet__count-warn {
  color: #b91c1c;
  font-weight: 600;
  margin-left: 0.25rem;
}

.rc-sheet__bulk {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
  background: var(--p-surface-50, #fafafa);
}
.rc-sheet__notify-toggle {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
}

.rc-sheet__list {
  flex: 1 1 auto;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

.rc-sheet__empty {
  padding: 2rem 1rem;
  text-align: center;
  color: var(--p-text-muted-color, #6b7280);
}

.rc-sheet__footer {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--p-content-border-color, #e5e7eb);
  background: var(--p-content-background, #fff);
  position: sticky;
  bottom: 0;
}
.rc-sheet__footer :nth-child(2) {
  margin-left: auto;
  min-width: 9rem;
}
</style>
