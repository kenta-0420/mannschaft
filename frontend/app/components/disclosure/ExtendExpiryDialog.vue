<script setup lang="ts">
import dayjs from 'dayjs'
import type { DisclosureExport } from '~/types/disclosure'

/**
 * 出力履歴の保管期限延長ダイアログ（F09.14 Phase 4-B）。
 *
 * <p>設計書 §5.7「出力ファイル保管期間に基づく延長」に対応。
 * 既存ドラフト編集 UI と同じ PrimeVue + Tailwind 規律で実装する。</p>
 *
 * <h3>クライアント側バリデーション</h3>
 * <ul>
 *   <li>過去日時不可（現在時刻より未来であること）</li>
 *   <li>本日から 7 年以内（バックエンド DISCLOSURE_011 と整合）</li>
 * </ul>
 *
 * <p>サーバー側 422 (DISCLOSURE_011) など想定外の検証エラーは
 * トーストで通知する。</p>
 */

const props = defineProps<{
  organizationId: string
  export: DisclosureExport
  open: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  extended: [updated: DisclosureExport]
}>()

const { t } = useI18n()
const { success, error: notifyError } = useNotification()
const { userTimezone, buildOffsetDateTimeStr } = useDatetime()

const orgIdRef = computed(() => props.organizationId)
const api = computed(() => useDisclosureApi(orgIdRef.value))

/** 7 年（うるう年は無視。バックエンドと同じ「7年加算」運用）。 */
const MAX_EXTEND_YEARS = 7

/** Calendar v-model 用の Date。 */
const newExpiresAt = ref<Date | null>(null)
const submitting = ref(false)

/** 「本日から 7 年後」の最大日付。 */
const maxDate = computed<Date>(() => {
  const d = new Date()
  d.setFullYear(d.getFullYear() + MAX_EXTEND_YEARS)
  return d
})

/** Calendar の最小日付（現在時刻）。 */
const minDate = computed<Date>(() => new Date())

/** 過去日時バリデーション。 */
const isFuture = computed(() => {
  if (!newExpiresAt.value) return false
  return newExpiresAt.value.getTime() > Date.now()
})

/** 7 年以内バリデーション。 */
const isWithin7Years = computed(() => {
  if (!newExpiresAt.value) return false
  return newExpiresAt.value.getTime() <= maxDate.value.getTime()
})

/** 入力ありかつ全バリデーション通過。 */
const canSubmit = computed(() =>
  newExpiresAt.value !== null && isFuture.value && isWithin7Years.value,
)

/** バリデーションエラーメッセージ（フォーム下部表示）。 */
const validationMessage = computed<string | null>(() => {
  if (!newExpiresAt.value) return null
  if (!isFuture.value) return t('disclosure.extend_expiry_validation_future')
  if (!isWithin7Years.value) return t('disclosure.extend_expiry_validation_max_7_years')
  return null
})

/**
 * ダイアログ open 時に初期値（現在の expiresAt + 90 日）をセット。
 * expiresAt が null の場合は本日 + 90 日。
 */
watch(
  () => props.open,
  (visible) => {
    if (!visible) return
    const base = props.export.expiresAt ? new Date(props.export.expiresAt) : new Date()
    base.setDate(base.getDate() + 90)
    // 7 年を超えないよう丸める（既存 expiresAt が既に 7 年近い場合）
    if (base.getTime() > maxDate.value.getTime()) {
      newExpiresAt.value = new Date(maxDate.value.getTime())
    }
    else {
      newExpiresAt.value = base
    }
  },
  { immediate: true },
)

function close() {
  emit('update:open', false)
}

function formatDate(d: Date): string {
  return dayjs(d).tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
}

function currentExpiryLabel(): string {
  if (!props.export.expiresAt) return '-'
  const d = dayjs(props.export.expiresAt)
  if (!d.isValid()) return props.export.expiresAt
  return d.tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
}

async function handleSubmit() {
  if (!canSubmit.value || !newExpiresAt.value) return
  submitting.value = true
  try {
    // Issue #2508: BE の LocalDateTime は受信時オフセットを無視するため、
    // ユーザーTZのオフセット付き ISO 文字列を明示的に送る（useDatetime の共通道具を使用）。
    const isoValue = buildOffsetDateTimeStr(newExpiresAt.value)
    if (!isoValue) return
    const updated = await api.value.extendExpiry(
      props.export.id,
      isoValue,
    )
    success(t('disclosure.extend_expiry_success', { date: formatDate(newExpiresAt.value) }))
    emit('extended', updated)
    close()
  }
  catch (e) {
    const msg = e instanceof Error ? e.message : undefined
    notifyError(t('disclosure.extend_expiry_error'), msg)
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="open"
    :header="t('disclosure.extend_expiry_dialog_title')"
    :style="{ width: '480px' }"
    modal
    data-testid="extend-expiry-dialog"
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- 現在の保管期限 -->
      <div>
        <p class="mb-1 text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('disclosure.extend_expiry_current_label') }}
        </p>
        <p
          class="text-sm text-surface-600 dark:text-surface-400"
          data-testid="extend-expiry-current"
        >
          {{ currentExpiryLabel() }}
        </p>
      </div>

      <!-- 新しい保管期限 -->
      <div>
        <label
          for="extend-expiry-input"
          class="mb-1 block text-sm font-medium"
        >
          {{ t('disclosure.extend_expiry_date_label') }}
        </label>
        <DatePicker
          id="extend-expiry-input"
          v-model="newExpiresAt"
          show-time
          show-icon
          show-button-bar
          date-format="yy-mm-dd"
          :min-date="minDate"
          :max-date="maxDate"
          class="w-full"
          data-testid="extend-expiry-date-input"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ t('disclosure.extend_expiry_hint') }}
        </p>
        <p
          v-if="validationMessage"
          class="mt-1 text-xs text-red-600 dark:text-red-400"
          data-testid="extend-expiry-validation-error"
        >
          {{ validationMessage }}
        </p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('disclosure.actions.cancel')"
        text
        severity="secondary"
        data-testid="extend-expiry-cancel"
        @click="close"
      />
      <Button
        :label="t('disclosure.extend_expiry')"
        icon="pi pi-clock"
        :loading="submitting"
        :disabled="!canSubmit || submitting"
        data-testid="extend-expiry-submit"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
