<script setup lang="ts">
import type { AdReportReason } from '~/types/adPreferences'

/**
 * F09.17 / F09.19.9 広告通報モーダル
 *
 * - reason 5 種から選択（OFFENSIVE / MISLEADING / SPAM / IRRELEVANT / OTHER）
 * - 任意の自由記述
 * - POST `/api/v1/me/ad-reports` で送信（メッセージ型 = campaignId / 運用型 = operationalCampaignId の XOR）
 * - 送信成功でモーダル自動クローズ + トースト通知
 * - 429 (受信者向けレート制限) の専用ハンドリング
 */

const props = defineProps<{
  visible: boolean
  /** メッセージ型キャンペーン ID（UUID）。運用型通報時は未指定 */
  campaignId?: string | null
  /** 運用型キャンペーン ID（数値）。メッセージ型通報時は未指定 */
  operationalCampaignId?: number | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const { t } = useI18n()
const notification = useNotification()
const deliveriesApi = useAdDeliveriesApi()

const reasons: AdReportReason[] = ['OFFENSIVE', 'MISLEADING', 'SPAM', 'IRRELEVANT', 'OTHER']

const selectedReason = ref<AdReportReason | null>(null)
const detail = ref('')
const submitting = ref(false)

watch(
  () => props.visible,
  (newValue) => {
    if (newValue) {
      // 開くたびにフォーム初期化
      selectedReason.value = null
      detail.value = ''
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
      campaignId: props.campaignId ?? undefined,
      operationalCampaignId: props.operationalCampaignId ?? undefined,
      channelType: 'BANNER',
      reasonCode: selectedReason.value,
      comment: detail.value.trim() || null,
    })
    notification.success(t('advertising.report_dialog.submitted'))
    emit('submitted')
    emit('update:visible', false)
  } catch (err) {
    const status = extractStatus(err)
    if (status === 429) {
      notification.warn(t('advertising.report_dialog.rate_limited'))
    } else {
      notification.error(t('advertising.report_dialog.title'), String(err))
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
    :header="t('advertising.report_dialog.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '90vw' }"
    :closable="!submitting"
    @update:visible="(v) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4">
      <p class="text-sm text-surface-500 dark:text-surface-300">
        {{ t('advertising.report_dialog.description') }}
      </p>

      <div class="flex flex-col gap-2">
        <label class="font-medium">{{ t('advertising.report_dialog.reason_label') }}</label>
        <div class="flex flex-col gap-2">
          <label
            v-for="code in reasons"
            :key="code"
            class="flex cursor-pointer items-center gap-2 rounded-md border border-surface-300 p-2 hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
          >
            <RadioButton
              v-model="selectedReason"
              :input-id="`reason-${code}`"
              :value="code"
              :name="'ad-report-reason'"
            />
            <span>{{ t(`advertising.report_reason.${code.toLowerCase()}`) }}</span>
          </label>
        </div>
      </div>

      <div class="flex flex-col gap-2">
        <label for="ad-report-detail" class="font-medium">
          {{ t('advertising.report_dialog.detail_label') }}
        </label>
        <Textarea
          id="ad-report-detail"
          v-model="detail"
          rows="3"
          maxlength="1000"
          :placeholder="t('advertising.report_dialog.detail_placeholder')"
          :disabled="submitting"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('advertising.report_dialog.cancel')"
        text
        :disabled="submitting"
        @click="handleCancel"
      />
      <Button
        :label="t('advertising.report_dialog.submit')"
        icon="pi pi-send"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="handleSubmit"
      />
    </template>
  </Dialog>
</template>
