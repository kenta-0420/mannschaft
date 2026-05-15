<script setup lang="ts">
/**
 * F17.1 村機能 — 通報 Dialog コンポーネント
 *
 * 投稿 / メッセージ / 村人 / 村 を対象に通報を送信する共通 Dialog。
 *
 * 設計書: docs/features/F17.1_village_community.md §4.11 / §6.2
 *
 * 重要な設計事項:
 *   - 通報者の個人特定情報は一切返らない（ReportResponse 型に reporterUserId なし）。
 *   - 1 時間 10 件のレートリミット (VILLAGE_009 / HTTP 429) を i18n で案内する。
 *   - reasonCode はバックエンドが小文字（spam/harassment/offensive/other）を想定。
 *     設計書 §4.11 サンプル "harassment" に準拠。
 *   - 「通報は匿名で扱われる」旨を必ず明示する（§6.2 通報者非開示）。
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'

import type {
  ReportCreateRequest,
  VillageReportTargetType,
} from '~/types/village'

const props = defineProps<{
  visible: boolean
  villageId: string
  targetType: VillageReportTargetType
  /** バックエンドが受け付ける `targetRefId` は文字列。番号 ID も呼出側で数値受け取りやすいよう number も許容して内部で文字列化 */
  targetRefId: string | number
}>()

const emit = defineEmits<{
  'update:visible': [boolean]
  submitted: []
}>()

const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError, showWarn } = useNotification()

// =============================================================================
// 定数
// =============================================================================

/** 詳細欄の最大文字数 */
const DETAIL_MAX = 500

/** 通報理由の選択肢（reasonCode は backend が受け付ける小文字値） */
const REASON_CODES = ['spam', 'harassment', 'offensive', 'other'] as const
type ReasonCode = typeof REASON_CODES[number]

interface ReasonOption {
  code: ReasonCode
  label: string
}

const reasonOptions = computed<ReasonOption[]>(() =>
  REASON_CODES.map(code => ({
    code,
    label: t(`village.report.reason.${code}`),
  })),
)

// =============================================================================
// フォーム状態
// =============================================================================

const reasonCode = ref<ReasonCode | null>(null)
const detail = ref<string>('')
const submitting = ref(false)

/** Dialog が閉じられたら入力をリセット */
watch(
  () => props.visible,
  (v) => {
    if (!v) {
      reasonCode.value = null
      detail.value = ''
      submitting.value = false
    }
  },
)

const detailLengthError = computed<string | null>(() => {
  if (detail.value.length > DETAIL_MAX) {
    return t('village.error.VILLAGE_029')
  }
  return null
})

const canSubmit = computed<boolean>(() => {
  if (submitting.value) return false
  if (!reasonCode.value) return false
  if (detailLengthError.value) return false
  return true
})

// =============================================================================
// エラー抽出（FE3 の create-request.vue と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody
  status?: number
  statusCode?: number
  response?: { status?: number; _data?: ApiErrorBody }
}

function extractApiError(err: unknown): { code: string | null, status: number | null } {
  if (typeof err !== 'object' || err === null) {
    return { code: null, status: null }
  }
  const e = err as ApiErrorEnvelope
  const body: ApiErrorBody | undefined = e.data ?? e.response?._data
  const code = body?.errorCode ?? body?.code ?? null
  const status = e.status ?? e.statusCode ?? e.response?.status ?? null
  return { code, status }
}

function translateApiError(code: string | null, status: number | null): string {
  // レートリミット (429) は VILLAGE_009 として案内
  if (status === 429 || code === 'VILLAGE_009') {
    return t('village.error.VILLAGE_009')
  }
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    if (msg && msg !== key) return msg
  }
  return t('village.error.generic')
}

// =============================================================================
// アクション
// =============================================================================

function closeDialog() {
  emit('update:visible', false)
}

async function submit() {
  if (!canSubmit.value || !reasonCode.value) return
  submitting.value = true
  try {
    const body: ReportCreateRequest = {
      targetType: props.targetType,
      targetRefId: String(props.targetRefId),
      reasonCode: reasonCode.value,
      detail: detail.value.trim() === '' ? null : detail.value.trim(),
    }
    await villageApi.createReport(props.villageId, body)
    showSuccess(t('village.report.dialog.submitted'))
    emit('submitted')
    closeDialog()
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    // レートリミットは警告レベルで表示
    if (status === 429 || code === 'VILLAGE_009') {
      showWarn(t('village.error.VILLAGE_009'))
    }
    else {
      showError(translateApiError(code, status))
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :closable="!submitting"
    :draggable="false"
    :header="t('village.report.dialog.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '960px': '75vw', '640px': '95vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4 py-2">
      <!-- 匿名性の説明 -->
      <div class="rounded border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-200">
        <i class="pi pi-info-circle mr-1" />
        {{ t('village.report.dialog.anonymous') }}
      </div>

      <!-- 対象種別ラベル -->
      <div class="text-xs text-surface-500">
        <span class="font-medium">{{ t('village.report.list.target') }}:</span>
        {{ t(`village.report.targetType.${targetType}`) }}
      </div>

      <!-- 通報理由 -->
      <div>
        <label for="report-reason" class="mb-1 block text-sm font-medium">
          {{ t('village.report.dialog.reason') }}
          <span class="text-red-600">*</span>
        </label>
        <Select
          id="report-reason"
          v-model="reasonCode"
          :options="reasonOptions"
          option-label="label"
          option-value="code"
          :placeholder="t('village.report.dialog.reason')"
          class="w-full"
          :disabled="submitting"
        />
      </div>

      <!-- 詳細 -->
      <div>
        <label for="report-detail" class="mb-1 block text-sm font-medium">
          {{ t('village.report.dialog.detail') }}
        </label>
        <Textarea
          id="report-detail"
          v-model="detail"
          :maxlength="DETAIL_MAX"
          :auto-resize="true"
          rows="4"
          class="w-full"
          :invalid="!!detailLengthError"
          :disabled="submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ detail.length }} / {{ DETAIL_MAX }}
        </p>
        <p v-if="detailLengthError" class="mt-1 text-xs text-red-600">
          {{ detailLengthError }}
        </p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="closeDialog"
      />
      <Button
        :label="t('village.action.report')"
        icon="pi pi-flag"
        severity="danger"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
