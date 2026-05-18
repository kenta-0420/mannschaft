<script setup lang="ts">
import type { AdChannelType } from '~/types/adMessagingCampaign'
import type { AdReportReasonCode } from '~/types/adModeration'

/**
 * F09.17 広告通報モーダル
 *
 * - reason_code 6 種から選択（OFFENSIVE / MISLEADING / IRRELEVANT / INAPPROPRIATE / SPAM / OTHER）
 * - 任意の自由記述
 * - POST `/api/v1/me/ad-reports` で送信
 * - 送信成功でモーダル自動クローズ + トースト通知
 * - 429 (受信者向けレート制限) の専用ハンドリング
 */

const props = defineProps<{
  visible: boolean
  campaignId: string
  channelType: AdChannelType
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const { t } = useI18n()
const notification = useNotification()
const deliveriesApi = useAdDeliveriesApi()

const reasonCodes: AdReportReasonCode[] = [
  'OFFENSIVE',
  'MISLEADING',
  'IRRELEVANT',
  'INAPPROPRIATE',
  'SPAM',
  'OTHER',
]

const selectedReason = ref<AdReportReasonCode | null>(null)
const comment = ref('')
const submitting = ref(false)

watch(
  () => props.visible,
  (newValue) => {
    if (newValue) {
      // 開くたびにフォーム初期化
      selectedReason.value = null
      comment.value = ''
      submitting.value = false
    }
  },
)

const canSubmit = computed(() => selectedReason.value !== null && !submitting.value)

interface FetchError {
  statusCode?: number
  response?: { status?: number }
}

function extractStatus(err: unknown): number | undefined {
  if (typeof err !== 'object' || err === null) return undefined
  const e = err as FetchError
  return e.statusCode ?? e.response?.status
}

async function handleSubmit() {
  if (!selectedReason.value) return
  submitting.value = true
  try {
    await deliveriesApi.createReport({
      campaignId: props.campaignId,
      channelType: props.channelType,
      reasonCode: selectedReason.value,
      comment: comment.value.trim() || undefined,
    })
    notification.success(t('advertising.report_modal.success'))
    emit('submitted')
    emit('update:visible', false)
  } catch (err) {
    const status = extractStatus(err)
    if (status === 429) {
      notification.warn(t('advertising.report_modal.rate_limited'))
    } else {
      notification.error(t('advertising.report_modal.title'), String(err))
    }
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="t('advertising.report_modal.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '90vw' }"
    :closable="!submitting"
    @update:visible="(v) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4">
      <p class="text-sm text-surface-500 dark:text-surface-300">
        {{ t('advertising.report_modal.description') }}
      </p>

      <div class="flex flex-col gap-2">
        <label class="font-medium">{{ t('advertising.report_modal.reason_label') }}</label>
        <div class="flex flex-col gap-2">
          <label
            v-for="code in reasonCodes"
            :key="code"
            class="flex cursor-pointer items-center gap-2 rounded-md border border-surface-300 p-2 hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
          >
            <RadioButton
              v-model="selectedReason"
              :input-id="`reason-${code}`"
              :value="code"
              :name="'ad-report-reason'"
            />
            <span>{{ t(`advertising.report_reason.${code}`) }}</span>
          </label>
        </div>
      </div>

      <div class="flex flex-col gap-2">
        <label for="ad-report-comment" class="font-medium">
          {{ t('advertising.report_modal.comment_label') }}
        </label>
        <Textarea
          id="ad-report-comment"
          v-model="comment"
          rows="3"
          maxlength="1000"
          :placeholder="t('advertising.report_modal.comment_placeholder')"
          :disabled="submitting"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('advertising.report_modal.cancel')"
        text
        :disabled="submitting"
        @click="handleCancel"
      />
      <Button
        :label="t('advertising.report_modal.submit')"
        icon="pi pi-send"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
