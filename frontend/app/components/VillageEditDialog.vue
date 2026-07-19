<script setup lang="ts">
/**
 * F17.1 村機能 — 村本体編集 Dialog コンポーネント
 *
 * 村長（HEADMAN）のみが利用可能。設計書 §4.1.3 (PATCH /api/v1/villages/{id}) を呼び出して
 * 村の基本情報（name / description / category / joinPolicy / visibility /
 * iconR2Key / coverR2Key / guidelineMd）を更新する。
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1.3
 *
 * 重要な設計事項:
 *   - slug は不変（既存 villages の slug を変更しないため、本 Dialog ではフォーム欄を出さない）。
 *   - type は OFFICIAL/COMMUNITY のままで運用上ほぼ変えないため、本 Dialog のスコープ外。
 *   - 名前(name)は必須・最大 100 文字。
 *   - 楽観的ロック競合（VILLAGE_018）は親に伝播。
 *   - 編集権限の出し分けは VillageHeader 側で既に制御済み。本 Dialog 側では再チェックしない。
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Textarea from 'primevue/textarea'

import type {
  VillageBulletinVisibility,
  VillageJoinPolicy,
  VillageResponse,
  VillageUpdateRequest,
  VillageVisibility,
} from '~/types/village'

/** 村紋 (monsho) は VillageResponse の拡張で扱う（既存 VillageResponse は変更禁止のため）。 */
type VillageWithMonsho = VillageResponse & { monshoR2Key?: string | null }

const props = defineProps<{
  visible: boolean
  village: VillageResponse
}>()

const emit = defineEmits<{
  'update:visible': [boolean]
  updated: [village: VillageResponse]
}>()

const { t } = useI18n()
const villageApi = useVillageApi()
const { showSuccess, showError } = useNotification()

// =============================================================================
// 定数
// =============================================================================

const NAME_MAX = 100
const DESCRIPTION_MAX = 1000
const CATEGORY_MAX = 50
const GUIDELINE_MAX = 20000
const R2KEY_MAX = 500

/** 村紋アップロードの FE バリデーション（BE の ALLOWED_MONSHO_MIME / MONSHO_MAX_BYTES と一致・#2355） */
const MONSHO_ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp']
const MONSHO_MAX_BYTES = 5 * 1024 * 1024

interface JoinPolicyOption {
  value: VillageJoinPolicy
  label: string
}

interface VisibilityOption {
  value: VillageVisibility
  label: string
}

interface BulletinVisibilityOption {
  value: VillageBulletinVisibility
  label: string
}

const joinPolicyOptions = computed<JoinPolicyOption[]>(() => [
  { value: 'FREE', label: t('village.joinPolicy.FREE') },
  { value: 'APPROVAL', label: t('village.joinPolicy.APPROVAL') },
])

const visibilityOptions = computed<VisibilityOption[]>(() => [
  { value: 'PUBLIC', label: t('village.visibility.PUBLIC') },
  { value: 'UNLISTED', label: t('village.visibility.UNLISTED') },
])

const bulletinVisibilityOptions = computed<BulletinVisibilityOption[]>(() => [
  { value: 'PUBLIC', label: t('village.bulletinVisibility.PUBLIC') },
  { value: 'MEMBERS_ONLY', label: t('village.bulletinVisibility.MEMBERS_ONLY') },
])

// =============================================================================
// フォーム状態
// =============================================================================

interface FormState {
  name: string
  description: string
  category: string
  joinPolicy: VillageJoinPolicy
  visibility: VillageVisibility
  bulletinVisibility: VillageBulletinVisibility
  iconR2Key: string
  coverR2Key: string
  guidelineMd: string
  /** 村紋 R2 キー（別 EP でファイル入稿・PATCH 本文には含めない） */
  monshoR2Key: string
}

function buildFormFromVillage(v: VillageResponse): FormState {
  return {
    name: v.name ?? '',
    description: v.description ?? '',
    category: v.category ?? '',
    joinPolicy: v.joinPolicy,
    visibility: v.visibility,
    bulletinVisibility: v.bulletinVisibility ?? 'MEMBERS_ONLY',
    iconR2Key: v.iconR2Key ?? '',
    coverR2Key: v.coverR2Key ?? '',
    guidelineMd: v.guidelineMd ?? '',
    monshoR2Key: (v as VillageWithMonsho).monshoR2Key ?? '',
  }
}

const form = ref<FormState>(buildFormFromVillage(props.village))
const submitting = ref(false)

// =============================================================================
// 村紋 (monsho) — ファイル入稿（presign → R2 直 PUT → PUT /monsho）(#2355)
// =============================================================================

const runtimeConfig = useRuntimeConfig()
const monshoFileInput = ref<HTMLInputElement | null>(null)
const monshoUploading = ref(false)

/** 生 r2Key に公開 base を1回だけ前置（二重前置しない・BE は署名 URL 化しない前提）。VillageHeader と同パターン。 */
function buildR2Url(r2Key: string | null): string | null {
  if (!r2Key) return null
  const base = runtimeConfig.public.r2PublicUrl as string | undefined
  if (!base) return null
  return `${base.replace(/\/$/, '')}/${r2Key}`
}

const monshoPreviewUrl = computed<string | null>(() =>
  buildR2Url(form.value.monshoR2Key ? form.value.monshoR2Key : null),
)

function triggerMonshoSelect() {
  if (monshoUploading.value || submitting.value) return
  monshoFileInput.value?.click()
}

async function onMonshoFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = '' // 同じファイルの再選択を許可
  if (!file) return

  // FE バリデーション（アップロード前・BE と一致）
  if (!MONSHO_ALLOWED_MIME.includes(file.type)) {
    showError(t('village.monsho.errorMime'))
    return
  }
  if (file.size > MONSHO_MAX_BYTES) {
    showError(t('village.monsho.errorSize'))
    return
  }

  monshoUploading.value = true
  try {
    const res = await villageApi.uploadMonsho(props.village.id, file)
    form.value.monshoR2Key = res.monshoR2Key ?? ''
    showSuccess(t('village.monsho.uploadSuccess'))
    emitMonshoUpdated(form.value.monshoR2Key || null)
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    monshoUploading.value = false
  }
}

async function removeMonsho() {
  if (monshoUploading.value || submitting.value) return
  monshoUploading.value = true
  try {
    await villageApi.deleteMonsho(props.village.id)
    form.value.monshoR2Key = ''
    showSuccess(t('village.monsho.removeSuccess'))
    emitMonshoUpdated(null)
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
  }
  finally {
    monshoUploading.value = false
  }
}

/** 村紋変更を親へ即時反映（Dialog は閉じない。親は villageData を差し替え VillageHeader を更新）。 */
function emitMonshoUpdated(monshoR2Key: string | null) {
  const merged: VillageWithMonsho = { ...(props.village as VillageWithMonsho), monshoR2Key }
  emit('updated', merged)
}

/** Dialog の visible 変化に応じてフォームを再初期化（毎回最新の村情報から） */
watch(
  () => props.visible,
  (v) => {
    if (v) {
      form.value = buildFormFromVillage(props.village)
      submitting.value = false
    }
  },
)

/** 村オブジェクト自体が差し替わった場合（親側で再ロード時）も同期 */
watch(
  () => props.village,
  (v) => {
    if (props.visible) {
      form.value = buildFormFromVillage(v)
    }
  },
)

// =============================================================================
// バリデーション
// =============================================================================

const nameError = computed<string | null>(() => {
  const v = form.value.name.trim()
  if (!v) return t('village.editDialog.errorNameRequired')
  if (v.length > NAME_MAX) return t('village.editDialog.errorNameTooLong', { max: NAME_MAX })
  return null
})

const descriptionError = computed<string | null>(() => {
  if (form.value.description.length > DESCRIPTION_MAX) {
    return t('village.editDialog.errorDescriptionTooLong', { max: DESCRIPTION_MAX })
  }
  return null
})

const categoryError = computed<string | null>(() => {
  if (form.value.category.length > CATEGORY_MAX) {
    return t('village.editDialog.errorCategoryTooLong', { max: CATEGORY_MAX })
  }
  return null
})

const guidelineError = computed<string | null>(() => {
  if (form.value.guidelineMd.length > GUIDELINE_MAX) {
    return t('village.editDialog.errorGuidelineTooLong', { max: GUIDELINE_MAX })
  }
  return null
})

const iconKeyError = computed<string | null>(() => {
  if (form.value.iconR2Key.length > R2KEY_MAX) {
    return t('village.editDialog.errorR2KeyTooLong', { max: R2KEY_MAX })
  }
  return null
})

const coverKeyError = computed<string | null>(() => {
  if (form.value.coverR2Key.length > R2KEY_MAX) {
    return t('village.editDialog.errorR2KeyTooLong', { max: R2KEY_MAX })
  }
  return null
})

const canSubmit = computed<boolean>(() => {
  if (submitting.value) return false
  if (nameError.value) return false
  if (descriptionError.value) return false
  if (categoryError.value) return false
  if (guidelineError.value) return false
  if (iconKeyError.value) return false
  if (coverKeyError.value) return false
  return true
})

// =============================================================================
// エラー抽出（FE3 / FE5 と同形）
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

function translateApiError(code: string | null, status: number | null): string {
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    if (msg && msg !== key) return msg
  }
  if (status === 403) return t('village.error.VILLAGE_024')
  return t('village.error.generic')
}

// =============================================================================
// アクション
// =============================================================================

function closeDialog() {
  emit('update:visible', false)
}

/** 空文字列は null として送信（Backend で「未指定」と区別するため） */
function emptyToNull(s: string): string | null {
  const trimmed = s.trim()
  return trimmed === '' ? null : trimmed
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const body: VillageUpdateRequest = {
      name: form.value.name.trim(),
      description: emptyToNull(form.value.description),
      category: emptyToNull(form.value.category),
      joinPolicy: form.value.joinPolicy,
      visibility: form.value.visibility,
      bulletinVisibility: form.value.bulletinVisibility,
      iconR2Key: emptyToNull(form.value.iconR2Key),
      coverR2Key: emptyToNull(form.value.coverR2Key),
      guidelineMd: emptyToNull(form.value.guidelineMd),
    }
    const updated = await villageApi.updateVillage(props.village.id, body)
    showSuccess(t('village.editDialog.saveSuccess'))
    emit('updated', updated)
    emit('update:visible', false)
  }
  catch (err) {
    const { code, status } = extractApiError(err)
    showError(translateApiError(code, status))
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
    :header="t('village.editDialog.title')"
    :style="{ width: '40rem' }"
    :breakpoints="{ '960px': '80vw', '640px': '95vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4 py-2">
      <!-- 名称（必須） -->
      <div>
        <label for="village-edit-name" class="mb-1 block text-sm font-medium">
          {{ t('village.field.name') }}
          <span class="text-red-600">*</span>
        </label>
        <InputText
          id="village-edit-name"
          v-model="form.name"
          :maxlength="NAME_MAX"
          class="w-full"
          :invalid="!!nameError"
          :disabled="submitting"
        />
        <p v-if="nameError" class="mt-1 text-xs text-red-600">
          {{ nameError }}
        </p>
      </div>

      <!-- 説明 -->
      <div>
        <label for="village-edit-description" class="mb-1 block text-sm font-medium">
          {{ t('village.field.description') }}
        </label>
        <Textarea
          id="village-edit-description"
          v-model="form.description"
          :maxlength="DESCRIPTION_MAX"
          :auto-resize="true"
          rows="3"
          class="w-full"
          :invalid="!!descriptionError"
          :disabled="submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ form.description.length }} / {{ DESCRIPTION_MAX }}
        </p>
        <p v-if="descriptionError" class="mt-1 text-xs text-red-600">
          {{ descriptionError }}
        </p>
      </div>

      <!-- カテゴリ -->
      <div>
        <label for="village-edit-category" class="mb-1 block text-sm font-medium">
          {{ t('village.field.category') }}
        </label>
        <InputText
          id="village-edit-category"
          v-model="form.category"
          :maxlength="CATEGORY_MAX"
          class="w-full"
          :invalid="!!categoryError"
          :disabled="submitting"
        />
        <p v-if="categoryError" class="mt-1 text-xs text-red-600">
          {{ categoryError }}
        </p>
      </div>

      <!-- 参加方式 / 公開範囲 -->
      <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div>
          <label for="village-edit-join-policy" class="mb-1 block text-sm font-medium">
            {{ t('village.field.joinPolicy') }}
          </label>
          <Select
            id="village-edit-join-policy"
            v-model="form.joinPolicy"
            :options="joinPolicyOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :disabled="submitting"
          />
        </div>
        <div>
          <label for="village-edit-visibility" class="mb-1 block text-sm font-medium">
            {{ t('village.field.visibility') }}
          </label>
          <Select
            id="village-edit-visibility"
            v-model="form.visibility"
            :options="visibilityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :disabled="submitting"
          />
        </div>
      </div>

      <!-- 掲示板公開範囲 -->
      <div>
        <label for="village-edit-bulletin-visibility" class="mb-1 block text-sm font-medium">
          {{ t('village.field.bulletinVisibility') }}
        </label>
        <Select
          id="village-edit-bulletin-visibility"
          v-model="form.bulletinVisibility"
          :options="bulletinVisibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          :disabled="submitting"
        />
      </div>

      <!-- アイコン / カバー R2 キー -->
      <div>
        <label for="village-edit-icon" class="mb-1 block text-sm font-medium">
          {{ t('village.editDialog.iconLabel') }}
        </label>
        <InputText
          id="village-edit-icon"
          v-model="form.iconR2Key"
          :maxlength="R2KEY_MAX"
          class="w-full"
          :placeholder="t('village.editDialog.r2KeyPlaceholder')"
          :invalid="!!iconKeyError"
          :disabled="submitting"
        />
        <p v-if="iconKeyError" class="mt-1 text-xs text-red-600">
          {{ iconKeyError }}
        </p>
      </div>
      <div>
        <label for="village-edit-cover" class="mb-1 block text-sm font-medium">
          {{ t('village.editDialog.coverLabel') }}
        </label>
        <InputText
          id="village-edit-cover"
          v-model="form.coverR2Key"
          :maxlength="R2KEY_MAX"
          class="w-full"
          :placeholder="t('village.editDialog.r2KeyPlaceholder')"
          :invalid="!!coverKeyError"
          :disabled="submitting"
        />
        <p v-if="coverKeyError" class="mt-1 text-xs text-red-600">
          {{ coverKeyError }}
        </p>
      </div>

      <!-- 村紋（家紋・ロゴ）— ファイル入稿（#2355） -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('village.monsho.label') }}
        </label>
        <div class="flex items-center gap-4">
          <!-- プレビュー（アップロード中はオーバーレイ） -->
          <div
            class="relative flex h-20 w-20 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-surface-300 bg-surface-50 dark:border-surface-600 dark:bg-surface-800"
          >
            <img
              v-if="monshoPreviewUrl"
              :src="monshoPreviewUrl"
              :alt="t('village.monsho.title')"
              class="h-full w-full object-contain"
            >
            <i v-else class="pi pi-image text-2xl text-surface-400" />
            <div
              v-if="monshoUploading"
              class="absolute inset-0 flex items-center justify-center bg-black/40"
            >
              <i class="pi pi-spin pi-spinner text-xl text-white" />
            </div>
          </div>

          <!-- 隠しファイル input + 操作ボタン -->
          <div class="flex flex-col gap-2">
            <input
              ref="monshoFileInput"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              class="hidden"
              @change="onMonshoFileChange"
            >
            <div class="flex flex-wrap gap-2">
              <Button
                :label="monshoPreviewUrl ? t('village.monsho.change') : t('village.monsho.upload')"
                icon="pi pi-upload"
                severity="secondary"
                size="small"
                :loading="monshoUploading"
                :disabled="submitting"
                @click="triggerMonshoSelect"
              />
              <Button
                v-if="monshoPreviewUrl"
                :label="t('village.monsho.remove')"
                icon="pi pi-trash"
                severity="danger"
                size="small"
                text
                :disabled="monshoUploading || submitting"
                @click="removeMonsho"
              />
            </div>
            <p class="text-xs text-surface-500">
              {{ monshoUploading ? t('village.monsho.uploading') : t('village.monsho.hint') }}
            </p>
          </div>
        </div>
      </div>

      <!-- ガイドライン -->
      <div>
        <label for="village-edit-guideline" class="mb-1 block text-sm font-medium">
          {{ t('village.editDialog.guidelineLabel') }}
        </label>
        <Textarea
          id="village-edit-guideline"
          v-model="form.guidelineMd"
          :maxlength="GUIDELINE_MAX"
          :auto-resize="true"
          rows="6"
          class="w-full"
          :invalid="!!guidelineError"
          :disabled="submitting"
        />
        <p class="mt-1 text-xs text-surface-500">
          {{ form.guidelineMd.length }} / {{ GUIDELINE_MAX }}
        </p>
        <p v-if="guidelineError" class="mt-1 text-xs text-red-600">
          {{ guidelineError }}
        </p>
      </div>
    </div>

    <template #footer>
      <!-- Phase 3: ニュースレター設定への遷移（HEADMAN 専用） -->
      <NuxtLink
        :to="`/villages/${props.village.id}/newsletter-settings`"
        class="mr-auto text-sm text-primary-600 hover:underline"
        @click="closeDialog"
      >
        <i class="pi pi-envelope mr-1" />
        {{ t('village.newsletter.settings') }}
      </NuxtLink>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        :disabled="submitting"
        @click="closeDialog"
      />
      <Button
        :label="t('village.editDialog.save')"
        icon="pi pi-check"
        severity="primary"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
