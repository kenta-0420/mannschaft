<script setup lang="ts">
/**
 * 予約通知メール宛先（機能D・F03.4 §4.D/§5.D）管理UI（ADMIN限定）
 *
 * - チーム単位で「予約が成立するたびにメール通知を送る宛先」を登録・管理する。
 *   宛先は非ユーザー（店の代表アドレス等）でも可。email＋label＋有効トグル。
 * - フリーミアム件数ゲート（BE 強制・FE は補助表示）:
 *     freeLimit=3  … 無料プランで登録できる上限（3件目まで）
 *     maxLimit=10  … 有料プランでの最大件数（10件で追加不可）
 *   件数は有効・無効を問わず全登録行で数える（totalCount が分母）。
 *   3件到達後の4件目は hasPaidPlan=false なら有料ロック表示、10件到達で追加不可。
 * - PII 注意喚起: 登録アドレスに予約者の氏名・来店日時が送信される旨を明示する。
 * - エラーは BE のコードで判定して適切表示（握りつぶし禁止）:
 *     RESERVATION_029(402) 有料必須 / RESERVATION_028(400) 上限超過 /
 *     RESERVATION_030(409) email 重複 / @Email(400) 形式不正。
 *
 * 最終ゲートは BE。FE のロックはあくまで補助（誤操作を減らす表示）。
 */
import type { components } from '~/types/generated'

type NotificationRecipientResponse = components['schemas']['NotificationRecipientResponse']

const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

// === 状態 ===
const loading = ref(false)
const submitting = ref(false)
const togglingIds = ref<string[]>([])

const recipients = ref<NotificationRecipientResponse[]>([])
const totalCount = ref(0)
const enabledCount = ref(0)
const freeLimit = ref(3)
const maxLimit = ref(10)
const hasPaidPlan = ref(false)

// === 追加フォーム ===
const newEmail = ref('')
const newLabel = ref('')

/** email 形式の簡易チェック（最終防御は BE の @Email）。空欄では false。 */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const isEmailValid = computed(() => EMAIL_RE.test(newEmail.value.trim()))

/** 上限（10件）到達＝これ以上追加不可（有料でも不可） */
const atMaxLimit = computed(() => totalCount.value >= maxLimit.value)

/**
 * 次の1件が有料ロックにかかるか。
 * 無料プランで既に freeLimit(3) 件登録済み → 4件目は有料必須。
 * 上限到達時は atMaxLimit を優先するためここでは false 扱いにしない（表示は上限を優先）。
 */
const needsPaidForNext = computed(
  () => !hasPaidPlan.value && totalCount.value >= freeLimit.value && !atMaxLimit.value,
)

/** 追加ボタンの無効化条件（FE 補助。最終ゲートは BE） */
const addDisabled = computed(
  () =>
    props.disabled
    || submitting.value
    || !isEmailValid.value
    || atMaxLimit.value
    || needsPaidForNext.value,
)

// === データ取得 ===
async function load() {
  loading.value = true
  try {
    const res = await reservationApi.listNotificationRecipients(props.teamId)
    const d = res.data
    recipients.value = d.recipients ?? []
    totalCount.value = d.totalCount ?? recipients.value.length
    enabledCount.value = d.enabledCount ?? 0
    freeLimit.value = d.freeLimit ?? 3
    maxLimit.value = d.maxLimit ?? 10
    hasPaidPlan.value = d.hasPaidPlan ?? false
  }
  catch {
    // 取得失敗は空表示（安全方向）。追加は BE ゲートが最終防御。
    recipients.value = []
    totalCount.value = 0
    enabledCount.value = 0
  }
  finally {
    loading.value = false
  }
}

// === エラーコード判定（握りつぶさない） ===
function extractErrorCode(err: unknown): string | undefined {
  return (err as { data?: { error?: { code?: string } } })?.data?.error?.code
}

function hasEmailFieldError(err: unknown): boolean {
  const fieldErrors = (err as { data?: { error?: { fieldErrors?: Array<{ field?: string }> } } })
    ?.data?.error?.fieldErrors
  return Array.isArray(fieldErrors) && fieldErrors.some(fe => fe.field === 'email')
}

/** 追加時のエラーを BE コードで分岐して利用者向けに表示する。 */
function notifyCreateError(err: unknown) {
  const code = extractErrorCode(err)
  switch (code) {
    case 'RESERVATION_029':
      // 402 Payment Required（無料プランで4件目以降）: 有料案内
      notification.warn(
        t('reservation.notify_recipients.error.paid_required_title'),
        t('reservation.notify_recipients.error.paid_required_detail'),
      )
      return
    case 'RESERVATION_028':
      // 400（上限10件超過）
      notification.error(
        t('dialog.error'),
        t('reservation.notify_recipients.error.limit_exceeded', { limit: maxLimit.value }),
      )
      return
    case 'RESERVATION_030':
      // 409（email 重複）
      notification.error(
        t('dialog.error'),
        t('reservation.notify_recipients.error.duplicate'),
      )
      return
    default:
      // @Email の 400（fieldErrors に email）はメール形式不正として表示
      if (hasEmailFieldError(err)) {
        notification.error(
          t('dialog.error'),
          t('reservation.notify_recipients.error.invalid_email'),
        )
        return
      }
      handleApiError(err)
  }
}

// === 追加 ===
async function add() {
  if (addDisabled.value) return
  submitting.value = true
  try {
    await reservationApi.createNotificationRecipient(props.teamId, {
      email: newEmail.value.trim(),
      label: newLabel.value.trim() || undefined,
      isEnabled: true,
    })
    notification.success(t('reservation.notify_recipients.message.create_success'))
    newEmail.value = ''
    newLabel.value = ''
    await load()
  }
  catch (err) {
    notifyCreateError(err)
  }
  finally {
    submitting.value = false
  }
}

// === 有効トグル ===
// 応答フィールドは `enabled`（BE getter isEnabled → JSON では enabled）。
// リクエストのフィールドは `isEnabled`（生成型 UpdateNotificationRecipientRequest）。両者を取り違えない。
async function toggleEnabled(recipient: NotificationRecipientResponse, next: boolean) {
  if (recipient.id == null || props.disabled) return
  togglingIds.value.push(recipient.id)
  try {
    await reservationApi.updateNotificationRecipient(props.teamId, recipient.id, {
      isEnabled: next,
    })
    await load()
  }
  catch (err) {
    handleApiError(err)
    // 失敗時は最新状態を再取得して UI をサーバー値へ戻す
    await load()
  }
  finally {
    togglingIds.value = togglingIds.value.filter(id => id !== recipient.id)
  }
}

function isToggling(recipient: NotificationRecipientResponse): boolean {
  return recipient.id != null && togglingIds.value.includes(recipient.id)
}

// === 削除 ===
async function remove(recipient: NotificationRecipientResponse) {
  if (recipient.id == null) return
  if (!confirm(t('reservation.notify_recipients.dialog.delete_confirm'))) return
  try {
    await reservationApi.deleteNotificationRecipient(props.teamId, recipient.id)
    notification.success(t('reservation.notify_recipients.message.delete_success'))
    await load()
  }
  catch (err) {
    handleApiError(err)
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-5">
    <!-- 使い方の一言 -->
    <Message severity="secondary" :closable="false" class="text-sm">
      {{ t('reservation.notify_recipients.help') }}
    </Message>

    <!-- PII 注意喚起（予約者氏名・来店日時が外部アドレスに送信される） -->
    <Message severity="warn" :closable="false" class="text-sm">
      <span class="flex items-start gap-2">
        <i class="pi pi-exclamation-triangle mt-0.5" />
        <span>{{ t('reservation.notify_recipients.pii_notice') }}</span>
      </span>
    </Message>

    <!-- === 追加フォーム === -->
    <div class="space-y-4 rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.notify_recipients.field.email') }}
        </label>
        <InputText
          v-model="newEmail"
          type="email"
          maxlength="255"
          :placeholder="t('reservation.notify_recipients.field.email_placeholder')"
          class="w-full"
          :disabled="disabled || submitting"
        />
      </div>

      <div>
        <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('reservation.notify_recipients.field.label') }}
        </label>
        <InputText
          v-model="newLabel"
          maxlength="100"
          :placeholder="t('reservation.notify_recipients.field.label_placeholder')"
          class="w-full"
          :disabled="disabled || submitting"
        />
      </div>

      <!-- 有料ロック（無料3件到達後の4件目） -->
      <Message
        v-if="needsPaidForNext"
        severity="info"
        :closable="false"
      >
        <div class="space-y-1">
          <p class="flex items-center gap-2 text-sm font-medium">
            <i class="pi pi-lock" />
            {{ t('reservation.notify_recipients.paid_lock.title', { free: freeLimit }) }}
          </p>
          <p class="text-xs text-surface-500">
            {{ t('reservation.notify_recipients.paid_lock.detail', { free: freeLimit, max: maxLimit }) }}
          </p>
        </div>
      </Message>

      <!-- 上限（10件）到達 -->
      <Message
        v-else-if="atMaxLimit"
        severity="warn"
        :closable="false"
        class="text-sm"
      >
        {{ t('reservation.notify_recipients.limit_reached', { max: maxLimit }) }}
      </Message>

      <div class="flex items-center gap-2">
        <Button
          :label="t('reservation.notify_recipients.button.add')"
          icon="pi pi-plus"
          size="small"
          :loading="submitting"
          :disabled="addDisabled"
          @click="add"
        />
        <span class="text-xs text-surface-500">
          {{ t('reservation.notify_recipients.count_status', { total: totalCount, max: maxLimit }) }}
        </span>
      </div>
    </div>

    <!-- === 登録済み一覧 === -->
    <div>
      <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-surface-500">
        {{ t('reservation.notify_recipients.list.title') }}
      </p>

      <div v-if="loading" class="space-y-2">
        <Skeleton height="2.5rem" width="100%" />
        <Skeleton height="2.5rem" width="100%" />
      </div>

      <p
        v-else-if="recipients.length === 0"
        class="rounded-lg border border-surface-200 p-4 text-center text-sm text-surface-500 dark:border-surface-700"
      >
        {{ t('reservation.notify_recipients.list.empty') }}
      </p>

      <ul
        v-else
        class="divide-y divide-surface-200 rounded-lg border border-surface-200 dark:divide-surface-700 dark:border-surface-700"
      >
        <li
          v-for="item in recipients"
          :key="item.id"
          class="flex flex-wrap items-center justify-between gap-2 p-3"
        >
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2 text-sm">
              <span class="font-medium text-surface-700 dark:text-surface-300">
                {{ item.email }}
              </span>
              <Tag
                v-if="item.label"
                :value="item.label"
                severity="secondary"
              />
              <Tag
                v-if="!item.enabled"
                :value="t('reservation.notify_recipients.list.disabled')"
                severity="warn"
              />
            </div>
          </div>
          <div class="flex items-center gap-3">
            <ToggleSwitch
              :model-value="item.enabled ?? false"
              :disabled="disabled || isToggling(item)"
              :aria-label="t('reservation.notify_recipients.toggle.aria', { email: item.email })"
              @update:model-value="(v: boolean) => toggleEnabled(item, v)"
            />
            <Button
              icon="pi pi-trash"
              severity="danger"
              text
              rounded
              size="small"
              :aria-label="t('reservation.notify_recipients.button.delete')"
              :disabled="disabled"
              @click="remove(item)"
            />
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
