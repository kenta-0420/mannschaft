<script setup lang="ts">
/**
 * F17.1 村機能 Phase 2 — 代表委任 Dialog コンポーネント
 *
 * チーム / 組織として村に加入しているメンバーシップに対して、
 * 個人ユーザーへ「代表として投稿する権限」を委任する Dialog。
 *
 * 設計書: docs/features/F17.1_village_community.md §3.11 / §13.2
 *
 * 重要な設計事項:
 *   - 委任は `village_representatives` テーブルへの INSERT を伴う。
 *   - Backend Controller は Phase 2 未実装のため、composable 経由で
 *     `/api/v1/villages/{vid}/representatives` を呼ぶ。
 *   - 委任先ユーザーは現状 ID 直入力（将来的に User Picker に差し替え予定）。
 *   - 成功時は `granted` を emit し、親側で一覧を再取得する想定。
 */
import type { VillageRepresentativeGrantRequest } from '~/types/village'

const props = defineProps<{
  visible: boolean
  villageId: string
  /** 対象メンバーシップ ID（TEAM / ORGANIZATION 加入の village_memberships.id） */
  membershipId: string
}>()

const emit = defineEmits<{
  'update:visible': [boolean]
  granted: []
}>()

const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError } = useNotification()

// =============================================================================
// 定数
// =============================================================================

/** メモ欄の最大文字数 */
const NOTE_MAX = 500

// =============================================================================
// フォーム状態
// =============================================================================

const representativeUserId = ref<number | null>(null)
const note = ref<string>('')
const submitting = ref(false)

/** Dialog が閉じられたら入力をリセット */
watch(
  () => props.visible,
  (v) => {
    if (!v) {
      representativeUserId.value = null
      note.value = ''
      submitting.value = false
    }
  },
)

const noteLengthError = computed<string | null>(() => {
  if (note.value.length > NOTE_MAX) {
    return t('village.error.VILLAGE_029')
  }
  return null
})

const canSubmit = computed<boolean>(() => {
  if (submitting.value) return false
  if (representativeUserId.value === null || representativeUserId.value <= 0) return false
  if (noteLengthError.value) return false
  return true
})

// =============================================================================
// エラー抽出（VillageReportDialog と同形）
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
  response?: { status?: number, _data?: ApiErrorBody }
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

function translateApiError(code: string | null): string {
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
  if (!canSubmit.value || representativeUserId.value === null) return
  submitting.value = true
  try {
    const body: VillageRepresentativeGrantRequest = {
      membershipId: props.membershipId,
      representativeUserId: representativeUserId.value,
      note: note.value.trim() === '' ? null : note.value.trim(),
    }
    await villageApi.grantRepresentative(props.villageId, body)
    showSuccess(t('village.representative.grant'))
    emit('granted')
    closeDialog()
  }
  catch (err) {
    const { code } = extractApiError(err)
    showError(translateApiError(code))
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
    :header="t('village.representative.grant')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '960px': '75vw', '640px': '95vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4 py-2">
      <!-- 委任先ユーザー ID -->
      <div>
        <label for="grant-user-id" class="mb-1 block text-sm font-medium">
          {{ t('village.representative.title') }}
          <span class="text-red-600">*</span>
        </label>
        <InputNumber
          id="grant-user-id"
          v-model="representativeUserId"
          :use-grouping="false"
          :min="1"
          :placeholder="t('village.representative.title')"
          class="w-full"
          :disabled="submitting"
        />
      </div>

      <!-- メモ -->
      <div>
        <label for="grant-note" class="mb-1 block text-sm font-medium">
          {{ t('village.representative.note') }}
        </label>
        <Textarea
          id="grant-note"
          v-model="note"
          :maxlength="NOTE_MAX"
          :auto-resize="true"
          rows="3"
          class="w-full"
          :invalid="!!noteLengthError"
          :disabled="submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ note.length }} / {{ NOTE_MAX }}
        </p>
        <p v-if="noteLengthError" class="mt-1 text-xs text-red-600">
          {{ noteLengthError }}
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
        :label="t('village.representative.grant')"
        icon="pi pi-user-plus"
        severity="primary"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
