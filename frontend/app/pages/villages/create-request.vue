<script setup lang="ts">
/**
 * F17.1 村機能 — 任意村作成申請フォーム
 *
 * 設計書: docs/features/F17.1_village_community.md §4.6
 *
 * 画面構成:
 *   A. 申請フォーム（ガイドライン同意 + 必須項目）
 *   B. 自分の申請一覧（PENDING の場合は取下げ可能）
 *
 * Backend Controller:
 *   - POST /api/v1/villages/creation-requests
 *   - GET  /api/v1/me/village-creation-requests
 *   - POST /api/v1/admin/village-creation-requests/{id}/withdraw
 */
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Column from 'primevue/column'
import DataTable from 'primevue/datatable'
import InputText from 'primevue/inputtext'
import Popover from 'primevue/popover'
import RadioButton from 'primevue/radiobutton'
import Tag from 'primevue/tag'
import Textarea from 'primevue/textarea'

import type {
  VillageCreationRequestCreateRequest,
  VillageCreationRequestResponse,
  VillageJoinPolicy,
  VillageRequestStatus,
  VillageVisibility,
} from '~/types/village'

definePageMeta({
  layout: 'default',
  middleware: 'auth',
})

const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError, showWarn } = useNotification()
const { formatDateTime } = useDatetime()

// =============================================================================
// 定数
// =============================================================================

/** 村名長さ上限（設計書 §3.3） */
const NAME_MAX = 80
const NAME_MIN = 1
/** スラッグ形式（Backend バリデーションと同一） */
const SLUG_PATTERN = /^[a-z0-9-]{3,40}$/
const SLUG_MAX = 40
const PURPOSE_MAX = 500
const CATEGORY_MAX = 40

// =============================================================================
// フォーム状態
// =============================================================================

const guidelineAgreed = ref(false)
const guidelineAgreedAt = ref<string | null>(null)

watch(guidelineAgreed, (agreed) => {
  guidelineAgreedAt.value = agreed ? new Date().toISOString() : null
})

const formName = ref('')
const formSlug = ref('')
const formCategory = ref('')
const formPurpose = ref('')
const formJoinPolicy = ref<VillageJoinPolicy>('FREE')
const formVisibility = ref<VillageVisibility>('PUBLIC')

const submitting = ref(false)

// =============================================================================
// スラッグ Popover
// =============================================================================

const slugHelpPopover = ref()
const toggleSlugHelp = (event: Event) => {
  slugHelpPopover.value?.toggle(event)
}

// =============================================================================
// 一覧状態
// =============================================================================

const myRequests = ref<VillageCreationRequestResponse[]>([])
const listLoading = ref(false)
/** 取下げ進行中の申請 ID（多重クリック防止） */
const withdrawingIds = ref<Set<string>>(new Set())

// =============================================================================
// バリデーション
// =============================================================================

const nameError = computed<string | null>(() => {
  const v = formName.value.trim()
  if (v.length < NAME_MIN || v.length > NAME_MAX) {
    return t('village.error.VILLAGE_029')
  }
  return null
})

const slugError = computed<string | null>(() => {
  const v = formSlug.value.trim()
  if (!v) return t('village.error.VILLAGE_029')
  if (!SLUG_PATTERN.test(v)) return t('village.error.VILLAGE_004')
  return null
})

const categoryError = computed<string | null>(() => {
  const v = formCategory.value.trim()
  if (!v) return t('village.error.VILLAGE_029')
  if (v.length > CATEGORY_MAX) return t('village.error.VILLAGE_029')
  return null
})

const purposeError = computed<string | null>(() => {
  const v = formPurpose.value.trim()
  if (!v) return t('village.error.VILLAGE_029')
  if (v.length > PURPOSE_MAX) return t('village.error.VILLAGE_029')
  return null
})

const canSubmit = computed<boolean>(() => {
  if (!guidelineAgreed.value) return false
  if (submitting.value) return false
  if (nameError.value) return false
  if (slugError.value) return false
  if (categoryError.value) return false
  if (purposeError.value) return false
  return true
})

// =============================================================================
// エラー抽出
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

function extractApiError(err: unknown): { code: string | null; status: number | null } {
  if (typeof err !== 'object' || err === null) {
    return { code: null, status: null }
  }
  const e = err as ApiErrorEnvelope
  const body: ApiErrorBody | undefined = e.data ?? e.response?._data
  const code = body?.errorCode ?? body?.code ?? null
  const status = e.status ?? e.statusCode ?? e.response?.status ?? null
  return { code, status }
}

/**
 * エラーコードに対応する i18n キーがあれば翻訳メッセージを返す。
 * なければ generic を返す。
 */
function translateApiError(code: string | null, status: number | null): string {
  // レートリミット（429）優先
  if (status === 429) {
    return t('village.error.VILLAGE_010')
  }
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    // i18n がキー未定義のときキー文字列をそのまま返すケースに備えて fallback
    if (msg && msg !== key) return msg
  }
  return t('village.error.generic')
}

// =============================================================================
// 一覧ロード
// =============================================================================

async function loadRequests() {
  listLoading.value = true
  try {
    const res = await villageApi.listMyCreationRequests()
    myRequests.value = res
  } catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  } finally {
    listLoading.value = false
  }
}

// =============================================================================
// 送信
// =============================================================================

function resetForm() {
  formName.value = ''
  formSlug.value = ''
  formCategory.value = ''
  formPurpose.value = ''
  formJoinPolicy.value = 'FREE'
  formVisibility.value = 'PUBLIC'
  guidelineAgreed.value = false
  guidelineAgreedAt.value = null
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const body: VillageCreationRequestCreateRequest = {
      name: formName.value.trim(),
      slug: formSlug.value.trim(),
      category: formCategory.value.trim(),
      purpose: formPurpose.value.trim(),
      joinPolicy: formJoinPolicy.value,
      visibility: formVisibility.value,
      // 設計書 §3.6: 一般ユーザーが申請するのは常に COMMUNITY 村
      type: 'COMMUNITY',
      guidelineAgreedAt: guidelineAgreedAt.value!,
    }
    const res = await villageApi.createCreationRequest(body)
    // 自動承認により createdVillageId が返るので村詳細ページへリダイレクト
    const createdVillageId = res.createdVillageId
    showSuccess(t('village.creationRequest.submitted'))
    if (createdVillageId) {
      await navigateTo(`/villages/${createdVillageId}`)
      return
    }
    resetForm()
    await loadRequests()
  } catch (err) {
    const { code, status } = extractApiError(err)
    // ガイドライン未同意（VILLAGE_014）はバリデーション側でも防ぐが、
    // バックエンドが返した場合は警告として表示
    if (code === 'VILLAGE_014') {
      showWarn(t('village.error.VILLAGE_014'))
    } else if (status === 429 || code === 'VILLAGE_010') {
      showWarn(t('village.error.VILLAGE_010'))
    } else {
      showError(translateApiError(code, status))
    }
  } finally {
    submitting.value = false
  }
}

// =============================================================================
// 取下げ
// =============================================================================

async function withdraw(req: VillageCreationRequestResponse) {
  if (withdrawingIds.value.has(req.id)) return
  withdrawingIds.value.add(req.id)
  try {
    await villageApi.reviewCreationRequest(req.id, 'withdraw', { reviewComment: '' })
    showSuccess(t('village.creationRequest.withdrawn'))
    await loadRequests()
  } catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  } finally {
    withdrawingIds.value.delete(req.id)
  }
}

// =============================================================================
// 表示ヘルパ
// =============================================================================

function statusLabel(status: VillageRequestStatus): string {
  switch (status) {
    case 'PENDING':
      return t('village.creationRequest.pending')
    case 'APPROVED':
      return t('village.creationRequest.approved')
    case 'REJECTED':
      return t('village.creationRequest.rejected')
    case 'WITHDRAWN':
      return t('village.creationRequest.withdrawn')
  }
}

function statusSeverity(status: VillageRequestStatus): 'info' | 'success' | 'danger' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'secondary'
  }
}


// =============================================================================
// 初期化
// =============================================================================

onMounted(() => {
  void loadRequests()
})
</script>

<template>
  <div class="mx-auto max-w-3xl space-y-8 p-4">
    <!-- A. 申請フォーム -->
    <section>
      <h1 class="mb-4 text-2xl font-bold">
        {{ t('village.creationRequest.title') }}
      </h1>

      <form class="space-y-6" @submit.prevent="submit">
        <!-- ガイドライン同意 -->
        <div class="rounded border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-900">
          <h2 class="mb-2 text-base font-semibold">
            {{ t('village.field.guideline') }}
          </h2>
          <p class="mb-3 whitespace-pre-line text-sm text-surface-700 dark:text-surface-300">
            {{ t('village.creationRequest.guideline') }}
          </p>
          <div class="flex items-center gap-2">
            <Checkbox
              v-model="guidelineAgreed"
              input-id="guideline-agreed"
              :binary="true"
            />
            <label for="guideline-agreed" class="cursor-pointer text-sm">
              {{ t('village.creationRequest.guideline') }}
            </label>
          </div>
        </div>

        <!-- 村名 -->
        <div>
          <label for="village-name" class="mb-1 block text-sm font-medium">
            {{ t('village.field.name') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            id="village-name"
            v-model="formName"
            :maxlength="NAME_MAX"
            class="w-full"
            :invalid="!!nameError && formName.length > 0"
          />
          <p v-if="nameError && formName.length > 0" class="mt-1 text-xs text-red-600">
            {{ nameError }}
          </p>
          <p class="mt-1 text-xs text-surface-500">{{ formName.length }} / {{ NAME_MAX }}</p>
        </div>

        <!-- スラッグ -->
        <div>
          <label for="village-slug" class="mb-1 block text-sm font-medium">
            {{ t('village.field.slug') }}
            <span class="text-red-600">*</span>
            <!-- ヘルプボタン -->
            <button
              type="button"
              class="ml-1 inline-flex items-center text-gray-400 hover:text-gray-600"
              @click="toggleSlugHelp"
            >
              <i class="pi pi-question-circle text-sm" />
            </button>
            <Popover ref="slugHelpPopover">
              <div class="max-w-xs text-sm">
                <p class="mb-1 font-semibold">{{ t('village.slug.helpTitle') }}</p>
                <p>{{ t('village.slug.helpBody') }}</p>
                <p class="mt-2 font-mono text-xs text-gray-500">{{ t('village.slug.helpExample') }}</p>
              </div>
            </Popover>
          </label>
          <InputText
            id="village-slug"
            v-model="formSlug"
            :maxlength="SLUG_MAX"
            class="w-full"
            :invalid="!!slugError && formSlug.length > 0"
          />
          <p class="mt-1 text-xs text-surface-500">
            {{ t('village.error.VILLAGE_004') }}
          </p>
          <p v-if="slugError && formSlug.length > 0" class="mt-1 text-xs text-red-600">
            {{ slugError }}
          </p>
        </div>

        <!-- カテゴリ -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('village.field.category') }}
            <span class="text-red-600">*</span>
          </label>
          <VillageCategorySelect
            v-model="formCategory"
            :invalid="!!categoryError && formCategory.length > 0"
          />
          <p v-if="categoryError && formCategory.length > 0" class="mt-1 text-xs text-red-600">
            {{ categoryError }}
          </p>
        </div>

        <!-- 趣旨・運営目的 -->
        <div>
          <label for="village-purpose" class="mb-1 block text-sm font-medium">
            {{ t('village.creationRequest.purpose') }}
            <span class="text-red-600">*</span>
          </label>
          <Textarea
            id="village-purpose"
            v-model="formPurpose"
            :maxlength="PURPOSE_MAX"
            :auto-resize="true"
            rows="4"
            class="w-full"
            :invalid="!!purposeError && formPurpose.length > 0"
          />
          <p v-if="purposeError && formPurpose.length > 0" class="mt-1 text-xs text-red-600">
            {{ purposeError }}
          </p>
          <p class="mt-1 text-xs text-surface-500">{{ formPurpose.length }} / {{ PURPOSE_MAX }}</p>
        </div>

        <!-- 参加方式 -->
        <div>
          <span class="mb-2 block text-sm font-medium">
            {{ t('village.field.joinPolicy') }}
            <span class="text-red-600">*</span>
          </span>
          <div class="flex flex-wrap gap-4">
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="formJoinPolicy"
                input-id="join-policy-free"
                name="joinPolicy"
                value="FREE"
              />
              <label for="join-policy-free" class="cursor-pointer text-sm">
                {{ t('village.joinPolicy.FREE') }}
              </label>
            </div>
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="formJoinPolicy"
                input-id="join-policy-approval"
                name="joinPolicy"
                value="APPROVAL"
              />
              <label for="join-policy-approval" class="cursor-pointer text-sm">
                {{ t('village.joinPolicy.APPROVAL') }}
              </label>
            </div>
          </div>
        </div>

        <!-- 可視性 -->
        <div>
          <span class="mb-2 block text-sm font-medium">
            {{ t('village.field.visibility') }}
            <span class="text-red-600">*</span>
          </span>
          <div class="flex flex-wrap gap-4">
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="formVisibility"
                input-id="visibility-public"
                name="visibility"
                value="PUBLIC"
              />
              <label for="visibility-public" class="cursor-pointer text-sm">
                {{ t('village.visibility.PUBLIC') }}
              </label>
            </div>
            <div class="flex items-center gap-2">
              <RadioButton
                v-model="formVisibility"
                input-id="visibility-unlisted"
                name="visibility"
                value="UNLISTED"
              />
              <label for="visibility-unlisted" class="cursor-pointer text-sm">
                {{ t('village.visibility.UNLISTED') }}
              </label>
            </div>
          </div>
        </div>

        <!-- 送信ボタン -->
        <div class="flex justify-end">
          <Button
            type="submit"
            :label="t('village.action.create')"
            :disabled="!canSubmit"
            :loading="submitting"
          />
        </div>
      </form>
    </section>

    <!-- B. 自分の申請一覧 -->
    <section>
      <h2 class="mb-4 text-xl font-semibold">
        {{ t('village.creationRequest.title') }}
      </h2>

      <DataTable
        :value="myRequests"
        :loading="listLoading"
        data-key="id"
        striped-rows
        responsive-layout="scroll"
      >
        <template #empty>
          <div class="py-4 text-center text-sm text-surface-500">
            {{ t('village.creationRequest.empty') }}
          </div>
        </template>

        <Column :header="t('village.field.name')" field="name" />
        <Column :header="t('village.field.category')" field="category">
          <template #body="slotProps">
            {{ (slotProps.data as VillageCreationRequestResponse).category ?? '-' }}
          </template>
        </Column>
        <Column :header="t('village.report.list.status')">
          <template #body="slotProps">
            <Tag
              :value="statusLabel((slotProps.data as VillageCreationRequestResponse).status)"
              :severity="statusSeverity((slotProps.data as VillageCreationRequestResponse).status)"
            />
          </template>
        </Column>
        <Column :header="t('village.field.createdAt')">
          <template #body="slotProps">
            {{ formatDateTime((slotProps.data as VillageCreationRequestResponse).createdAt) }}
          </template>
        </Column>
        <Column :header="t('village.action.submit')">
          <template #body="slotProps">
            <Button
              v-if="(slotProps.data as VillageCreationRequestResponse).status === 'PENDING'"
              :label="t('village.action.withdraw')"
              severity="secondary"
              size="small"
              :loading="withdrawingIds.has((slotProps.data as VillageCreationRequestResponse).id)"
              @click="withdraw(slotProps.data as VillageCreationRequestResponse)"
            />
          </template>
        </Column>
      </DataTable>
    </section>
  </div>
</template>
