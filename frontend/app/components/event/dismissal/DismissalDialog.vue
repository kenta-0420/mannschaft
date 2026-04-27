<script setup lang="ts">
/**
 * F03.12 §16 解散通知ダイアログ。
 *
 * <p>イベント主催者がワンタップで解散通知を送るためのダイアログ。
 * - メッセージ（任意・最大 500 字）
 * - 実際の終了日時（DatePicker、デフォルト現在時刻）
 * - 保護者にも通知（ToggleSwitch、デフォルト ON）
 *
 * <p>送信は {@code useDismissal().submit()} に委譲し、オンライン送信成功・オフラインキュー積み
 * のいずれの場合も {@code submitted} を emit する。エラー時は composable 側でトーストが出る。</p>
 */
import type { DismissalRequest } from '~/types/care'

const props = defineProps<{
  teamId: number
  eventId: number
  open: boolean
  /** メッセージ初期値（i18n 由来のテキストを呼び出し側が組み立てて渡す）。省略時は空。 */
  defaultMessage?: string
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'submitted': []
}>()

const teamIdRef = computed(() => props.teamId)
const eventIdRef = computed(() => props.eventId)
const { send, loading, error } = useDismissal(teamIdRef, eventIdRef)

const message = ref<string>(props.defaultMessage ?? '')
const actualEndAt = ref<Date>(new Date())
const notifyGuardians = ref<boolean>(true)

// open が再度開かれるたびにフォームを初期化
watch(
  () => props.open,
  (next) => {
    if (next) {
      message.value = props.defaultMessage ?? ''
      actualEndAt.value = new Date()
      notifyGuardians.value = true
    }
  },
)

const visible = computed<boolean>({
  get: () => props.open,
  set: (v: boolean) => emit('update:open', v),
})

const messageTooLong = computed<boolean>(() => (message.value?.length ?? 0) > 500)

/** Date を ISO-8601 文字列に変換する。 */
function toIsoString(d: Date): string {
  return d.toISOString()
}

async function onSubmit() {
  if (loading.value) return
  if (messageTooLong.value) return

  const body: DismissalRequest = {
    message: message.value?.length > 0 ? message.value : undefined,
    actualEndAt: actualEndAt.value ? toIsoString(actualEndAt.value) : undefined,
    notifyGuardians: notifyGuardians.value,
  }

  // useDismissal.send の挙動:
  //   - オンライン成功 → DismissalStatusResponse
  //   - オフラインキュー積み成功 → null (error.value === null)
  //   - 送信/キュー失敗 → null (error.value !== null)
  await send(body)

  // 失敗時はダイアログを閉じず再試行を許す。エラートーストは composable 側で出ている。
  if (error.value) {
    return
  }

  emit('submitted')
  visible.value = false
}

function onCancel() {
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('event.dismissal.title')"
    :style="{ width: '480px' }"
    modal
    :closable="!loading"
  >
    <div class="flex flex-col gap-4">
      <!-- メッセージ -->
      <div>
        <label class="mb-1 block text-sm font-medium" for="dismissal-message">
          {{ $t('event.dismissal.message_label') }}
        </label>
        <Textarea
          id="dismissal-message"
          v-model="message"
          rows="3"
          class="w-full"
          :placeholder="$t('event.dismissal.defaultMessage')"
          maxlength="500"
        />
        <p
          class="mt-1 text-right text-xs"
          :class="messageTooLong ? 'text-red-600' : 'text-surface-400'"
        >
          {{ message?.length ?? 0 }} / 500
        </p>
      </div>

      <!-- 実際の終了日時 -->
      <div>
        <label class="mb-1 block text-sm font-medium" for="dismissal-actual-end">
          {{ $t('event.dismissal.actual_end_at_label') }}
        </label>
        <DatePicker
          id="dismissal-actual-end"
          v-model="actualEndAt"
          show-time
          date-format="yy/mm/dd"
          class="w-full"
          show-icon
        />
      </div>

      <!-- 保護者にも通知 -->
      <div class="flex items-center justify-between">
        <label class="text-sm font-medium" for="dismissal-notify-guardians">
          {{ $t('event.dismissal.notify_guardians_toggle') }}
        </label>
        <ToggleSwitch
          v-model="notifyGuardians"
          input-id="dismissal-notify-guardians"
          data-testid="dismissal-notify-guardians"
        />
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="$t('common.cancel')"
          severity="secondary"
          :disabled="loading"
          @click="onCancel"
        />
        <Button
          :label="$t('event.dismissal.send_button')"
          severity="primary"
          icon="pi pi-send"
          :loading="loading"
          :disabled="messageTooLong"
          data-testid="dismissal-submit"
          @click="onSubmit"
        />
      </div>
    </template>
  </Dialog>
</template>
