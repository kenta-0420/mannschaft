<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import dayjs from 'dayjs'
import type { ClosingSettingsResponse } from '~/types/shiftBudget'

/**
 * 締め日設定画面 (`/admin/shift-budget/settings`)。CMP-260909-2152 PR-α。
 *
 * <p>組織の月次締め期間の締め日（1〜28 と末日）を参照・変更する。変更は BUDGET_ADMIN のみ。
 * 適用開始に選べる最も早い期間はサーバーが返す min_effective_period_key を使い、画面では計算しない。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()
const { getFieldErrors } = useErrorHandler()
const { canManageBudget, ensureLoaded: ensureBudgetAdminAccess } = useShiftBudgetAdminAccess()

/** 適用開始に選べる期間数（最も早い期間から数える） */
const EFFECTIVE_PERIOD_COUNT = 24
/** 締め日の値。0 は末日 */
const END_OF_MONTH = 0
const MAX_CLOSING_DAY = 28
/** 変更理由の最大文字数（API の ClosingDayChangeRequest.reason と同じ） */
const REASON_MAX = 500

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const settings = ref<ClosingSettingsResponse | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const loadForbidden = ref(false)
const submitting = ref(false)
const submitError = ref('')
/** 400 の項目別エラー（field -> message） */
const fieldErrors = ref<Record<string, string>>({})

const form = ref<{ day: number | null, effective: string | null, reason: string }>({
  day: null,
  effective: null,
  reason: '',
})

function dayLabel(day: number): string {
  return day === END_OF_MONTH
    ? t('shiftBudget.closingDay.endOfMonth')
    : t('shiftBudget.closingDay.dayOption', { day })
}

const dayOptions = computed(() => [
  ...Array.from({ length: MAX_CLOSING_DAY }, (_, i) => i + 1),
  END_OF_MONTH,
].map(value => ({ value, label: dayLabel(value) })))

const effectiveOptions = computed(() => {
  const min = settings.value?.min_effective_period_key
  if (!min) return []
  const first = dayjs(`${min}-01`)
  return Array.from({ length: EFFECTIVE_PERIOD_COUNT }, (_, i) => {
    const key = first.add(i, 'month').format('YYYY-MM')
    return { value: key, label: key }
  })
})

/** 期間キー M の締め日（締め日 0 は末日）。 */
function closingDate(monthKey: string, day: number): dayjs.Dayjs {
  const month = dayjs(`${monthKey}-01`)
  return day === END_OF_MONTH ? month.endOf('month') : month.date(day)
}

/** 期間キー M の期間（M-1 の締め日の翌日〜M の締め日）。 */
function periodText(monthKey: string, previousDay: number, day: number): string {
  const previousMonth = dayjs(`${monthKey}-01`).subtract(1, 'month').format('YYYY-MM')
  const start = closingDate(previousMonth, previousDay).add(1, 'day')
  const end = closingDate(monthKey, day)
  return `${start.format('YYYY-MM-DD')} – ${end.format('YYYY-MM-DD')}`
}

/**
 * 期間キー M に有効な締め日。変更履歴（effective_period_key <= M のうち revision 最大）から解決し、無ければ末日。
 * 予約済みの将来変更も履歴に含まれるので、今日の締め日ではなく期間ごとに引く（BE の期間算出と同じ規則）。
 */
function closingDayOf(monthKey: string): number {
  let best: { revision: number, day: number } | null = null
  for (const c of settings.value?.changes ?? []) {
    if (c.effective_period_key <= monthKey && (best === null || c.revision > best.revision)) {
      best = { revision: c.revision, day: c.closing_day }
    }
  }
  return best === null ? END_OF_MONTH : best.day
}

const preview = computed(() => {
  const { day, effective } = form.value
  if (!settings.value || day === null || effective === null) return null
  const previousMonth = dayjs(`${effective}-01`).subtract(1, 'month').format('YYYY-MM')
  const previousDay = closingDayOf(previousMonth)
  return {
    before: periodText(effective, previousDay, closingDayOf(effective)),
    after: periodText(effective, previousDay, day),
  }
})

const canSubmit = computed(() =>
  canManageBudget.value
  && !submitting.value
  && form.value.day !== null
  && form.value.effective !== null
  && form.value.reason.trim() !== '',
)

/**
 * 取得の世代。組織切替や再取得のたびに進め、応答の反映時に「取得時の組織が現在の組織で、かつ最新の取得」かを確かめる
 * （CMP-260909-2152 Codex 検分3 P2-2）。旧組織の応答が後着して新組織の締め日・履歴・revision を上書きし、
 * 旧 revision で新組織へ送信する事故を防ぐ。
 */
let loadGeneration = 0

/**
 * 組織の世代。組織を切り替えるたびに進める。変更の送信時に組織と世代を控え、応答の反映時に現在の画面と一致するときだけ
 * フォーム・エラーを更新する（CMP-260909-2152 α 検分 P2-3）。旧組織への送信の成功応答が新組織の入力を消したり、
 * 失敗・409 が新組織の画面にエラーや再取得・入力の補正を起こしたりしないようにする。
 */
let scopeGeneration = 0

function statusOf(e: unknown): number | undefined {
  return (e as { statusCode?: number, status?: number }).statusCode
    ?? (e as { status?: number }).status
}

async function load() {
  const generation = ++loadGeneration
  const orgId = organizationId.value
  if (!orgId) {
    settings.value = null
    loading.value = false
    loadFailed.value = false
    loadForbidden.value = false
    return
  }
  const isCurrent = () => generation === loadGeneration && orgId === organizationId.value
  loading.value = true
  loadFailed.value = false
  loadForbidden.value = false
  try {
    const res = await api.getClosingSettings(orgId)
    if (!isCurrent()) return
    settings.value = res
  }
  catch (e) {
    if (!isCurrent()) return
    settings.value = null
    if (statusOf(e) === 403) loadForbidden.value = true
    else loadFailed.value = true
  }
  finally {
    if (isCurrent()) loading.value = false
  }
}

function resetForm() {
  form.value = { day: null, effective: null, reason: '' }
}

async function submit() {
  if (!organizationId.value || !settings.value || !canSubmit.value) return
  const { day, effective, reason } = form.value
  if (day === null || effective === null) return
  const orgId = organizationId.value
  const generation = scopeGeneration
  const isCurrentScope = () => generation === scopeGeneration && orgId === organizationId.value
  submitting.value = true
  submitError.value = ''
  fieldErrors.value = {}
  try {
    await api.changeClosingSetting(orgId, {
      expected_revision: settings.value.revision,
      closing_day: day,
      effective_period_key: effective,
      reason: reason.trim(),
    })
    // 変更は受理された（組織を切り替えていても成功は知らせる）。フォームと再取得は送信時の組織の画面のときだけ
    notification.success(t('shiftBudget.closingDay.success'))
    if (!isCurrentScope()) return
    resetForm()
    await load()
  }
  catch (e) {
    if (!isCurrentScope()) return
    const status = statusOf(e)
    submitError.value = status === 409
      ? t('shiftBudget.closingDay.conflict')
      : t('shiftBudget.closingDay.error')
    if (status === 400) fieldErrors.value = getFieldErrors(e)
    if (status === 409) {
      // revision 競合: 画面が古いままだと再送しても 409 が続くため、最新の現在値・履歴・revision を取り直す。
      // 入力は保持する（設計書に定めなし。差分を見て再送できるようにする）。
      await load()
      if (!isCurrentScope()) return
      const min = settings.value?.min_effective_period_key
      if (form.value.effective !== null && (!min || form.value.effective < min)) {
        form.value.effective = null
      }
    }
  }
  finally {
    // 組織を切り替えた後は切替時に解除済み。新組織の送信中フラグを旧組織の応答で外さない
    if (isCurrentScope()) submitting.value = false
  }
}

function formatDateTime(value: string): string {
  return dayjs(value).tz(userTimezone.value).format('YYYY-MM-DD HH:mm')
}

watch(organizationId, () => {
  // 旧組織の設定（締め日・履歴・revision）と入力・エラー・送信中を破棄してから新組織を取得する
  scopeGeneration++
  submitting.value = false
  settings.value = null
  submitError.value = ''
  fieldErrors.value = {}
  resetForm()
  load()
  ensureBudgetAdminAccess()
})
onMounted(() => {
  load()
  ensureBudgetAdminAccess()
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader
      :title="t('shiftBudget.closingDay.title')"
      :subtitle="t('shiftBudget.closingDay.description')"
      back-to="/admin/shift-budget/dashboard"
    />

    <Message v-if="!organizationId" severity="warn" :closable="false" class="my-4">
      {{ t('shiftBudget.closingDay.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading && !settings" />

    <Message
      v-else-if="loadForbidden"
      severity="warn"
      :closable="false"
      class="my-4"
      data-testid="closing-settings-forbidden"
    >
      <p class="font-medium">{{ t('loadErrorState.states.forbidden.title') }}</p>
      <p>{{ t('loadErrorState.states.forbidden.message') }}</p>
    </Message>

    <Message
      v-else-if="loadFailed"
      severity="error"
      :closable="false"
      class="my-4"
      data-testid="closing-settings-load-error"
    >
      {{ t('shiftBudget.closingDay.loadError') }}
    </Message>

    <div v-else-if="settings" class="mt-4 flex flex-col gap-4">
      <SectionCard>
        <p class="text-sm text-surface-500">{{ t('shiftBudget.closingDay.current') }}</p>
        <p class="text-2xl font-bold" data-testid="closing-current">{{ dayLabel(settings.closing_day) }}</p>
      </SectionCard>

      <SectionCard :title="t('shiftBudget.closingDay.submit')">
        <Message
          v-if="!canManageBudget"
          severity="info"
          :closable="false"
          class="mb-3"
          data-testid="closing-readonly-notice"
        >
          {{ t('shiftBudget.closingDay.readOnly') }}
        </Message>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.dayLabel') }}</label>
            <Select
              v-model="form.day"
              data-testid="closing-day-select"
              :options="dayOptions"
              option-label="label"
              option-value="value"
              :disabled="!canManageBudget"
              class="w-full"
            />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.effectivePeriod') }}</label>
            <Select
              v-model="form.effective"
              data-testid="closing-effective-select"
              :options="effectiveOptions"
              option-label="label"
              option-value="value"
              :disabled="!canManageBudget"
              class="w-full"
            />
          </div>
        </div>
        <div class="mt-4">
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.closingDay.reason') }}</label>
          <Textarea
            v-model="form.reason"
            data-testid="closing-reason"
            rows="3"
            :maxlength="REASON_MAX"
            :disabled="!canManageBudget"
            class="w-full"
          />
          <p class="mt-1 text-right text-xs text-surface-400" data-testid="closing-reason-count">
            {{ form.reason.length }} / {{ REASON_MAX }}
          </p>
          <p
            v-if="fieldErrors.reason"
            class="mt-1 text-xs text-red-500"
            data-testid="closing-field-error-reason"
          >
            {{ fieldErrors.reason }}
          </p>
          <p v-if="canManageBudget && form.reason.trim() === ''" class="mt-1 text-xs text-surface-500">
            {{ t('shiftBudget.closingDay.reasonRequired') }}
          </p>
        </div>

        <div v-if="preview" class="mt-4 rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-900">
          <p class="mb-2 font-medium">{{ t('shiftBudget.closingDay.previewTitle') }}</p>
          <p>
            <span class="text-surface-500">{{ t('shiftBudget.closingDay.previewBefore') }}</span>
            <span class="ml-2" data-testid="closing-preview-before">{{ preview.before }}</span>
          </p>
          <p>
            <span class="text-surface-500">{{ t('shiftBudget.closingDay.previewAfter') }}</span>
            <span class="ml-2" data-testid="closing-preview-after">{{ preview.after }}</span>
          </p>
        </div>

        <ul v-if="Object.keys(fieldErrors).some(k => k !== 'reason')" class="mt-3 text-xs text-red-500" data-testid="closing-field-errors">
          <li v-for="(message, field) in fieldErrors" :key="field">
            <template v-if="field !== 'reason'">{{ field }}: {{ message }}</template>
          </li>
        </ul>
        <Message
          v-if="submitError"
          severity="error"
          :closable="false"
          class="mt-4"
          data-testid="closing-settings-error"
        >
          {{ submitError }}
        </Message>

        <div class="mt-4 flex justify-end">
          <Button
            data-testid="closing-submit"
            :label="t('shiftBudget.closingDay.submit')"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="submit"
          />
        </div>
      </SectionCard>

      <SectionCard :title="t('shiftBudget.closingDay.historyTitle')">
        <p
          v-if="settings.changes.length === 0"
          class="py-2 text-center text-surface-500"
          data-testid="closing-history-empty"
        >
          {{ t('shiftBudget.closingDay.historyEmpty') }}
        </p>
        <div v-else class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead>
              <tr class="border-b border-surface-200 dark:border-surface-700">
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.revision') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.appliedFrom') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.dayLabel') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.previousDay') }}</th>
                <th class="py-2 pr-3">{{ t('shiftBudget.closingDay.reason') }}</th>
                <th class="py-2">{{ t('shiftBudget.closingDay.changedAt') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="change in settings.changes"
                :key="change.revision"
                class="border-b border-surface-100 dark:border-surface-800"
                :data-testid="`closing-history-row-${change.revision}`"
              >
                <td class="py-2 pr-3">{{ change.revision }}</td>
                <td class="py-2 pr-3">{{ change.effective_period_key }}</td>
                <td class="py-2 pr-3">{{ dayLabel(change.closing_day) }}</td>
                <td class="py-2 pr-3">{{ dayLabel(change.previous_closing_day) }}</td>
                <td class="py-2 pr-3">{{ change.reason }}</td>
                <td class="py-2 whitespace-nowrap">{{ formatDateTime(change.created_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
