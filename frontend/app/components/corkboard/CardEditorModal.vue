<script setup lang="ts">
/**
 * F09.8 Phase C: コルクボードカード作成・編集モーダル。
 *
 * 4 種類のカード型 (REFERENCE / MEMO / URL / SECTION_HEADER) に対応する 1 枚物の
 * フォームコンポーネント。create / edit の両モードを 1 ファイルで扱う。
 *
 * 設計書 §4 `POST /api/v1/corkboards/{id}/cards` / `PUT .../cards/{cardId}` 準拠。
 *
 * 親 (`pages/corkboard/[id].vue`) からは:
 *   <CardEditorModal
 *     v-model:visible="isOpen"
 *     mode="create"
 *     :board-id="board.id"
 *     :default-position="{ x: 50, y: 50 }"
 *     @save="onCardSaved"
 *   />
 * のように使う。`mode='edit'` の場合は `card` を渡す。
 *
 * Phase C 範囲外:
 *  - REFERENCE 参照先の検索 UI (Phase G で本格実装)
 *  - URL OGP 取得プレビュー (バックエンドで作成時に取得済み)
 *  - 楽観的ロック (`version`) 競合の UX (現状サーバー 409 はトーストで通知のみ)
 */
import { useToast } from 'primevue/usetoast'
import type {
  CorkboardCardDetail,
  CorkboardCardType,
  CorkboardColor,
  CorkboardReferenceType,
  CreateCardRequest,
  UpdateCardRequest,
} from '~/types/corkboard'

interface Props {
  /** モーダル開閉状態 (v-model:visible) */
  visible: boolean
  /** create: 新規作成 / edit: 既存カード編集 */
  mode: 'create' | 'edit'
  /** 対象ボード ID (作成・更新の URL パスに使う) */
  boardId: number
  /** edit モード時の対象カード */
  card?: CorkboardCardDetail | null
  /** create モード時の初期座標 (省略時 0,0) */
  defaultPosition?: { x: number; y: number }
}

const props = withDefaults(defineProps<Props>(), {
  card: null,
  defaultPosition: () => ({ x: 0, y: 0 }),
})

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save', card: CorkboardCardDetail): void
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const toast = useToast()
const { captureQuiet } = useErrorReport()
const { createCard, updateCard } = useCorkboardApi()

// ----- フォーム状態 -----

const cardType = ref<CorkboardCardType>('MEMO')
const colorLabel = ref<CorkboardColor>('WHITE')
const positionX = ref<number>(0)
const positionY = ref<number>(0)

// REFERENCE 用
const referenceType = ref<CorkboardReferenceType>('TIMELINE_POST')
const referenceId = ref<number | null>(null)
/** F09.8 Phase G: URL から ID を抽出するための一時入力欄（送信対象外） */
const referenceUrlPaste = ref<string>('')
/** URL 抽出メッセージ（成功 / 失敗） */
const referenceUrlPasteMessage = ref<{ kind: 'success' | 'error'; text: string } | null>(null)

// MEMO / URL / SECTION_HEADER 共通
const title = ref<string>('')
const body = ref<string>('')
const url = ref<string>('')
const userNote = ref<string>('')

const saving = ref(false)
const errors = ref<Record<string, string>>({})

// ----- 種別オプション -----

const cardTypeOptions = computed(() => [
  { value: 'REFERENCE' as const, label: t('corkboard.modal.cardTypeReference') },
  { value: 'MEMO' as const, label: t('corkboard.modal.cardTypeMemo') },
  { value: 'URL' as const, label: t('corkboard.modal.cardTypeUrl') },
  { value: 'SECTION_HEADER' as const, label: t('corkboard.modal.cardTypeSectionHeader') },
])

const referenceTypeOptions = computed(() => [
  { value: 'TIMELINE_POST' as const, label: t('corkboard.modal.referenceTypeTimelinePost') },
  { value: 'BULLETIN_THREAD' as const, label: t('corkboard.modal.referenceTypeBulletinThread') },
  { value: 'BLOG_POST' as const, label: t('corkboard.modal.referenceTypeBlogPost') },
  { value: 'CHAT_MESSAGE' as const, label: t('corkboard.modal.referenceTypeChatMessage') },
  { value: 'FILE' as const, label: t('corkboard.modal.referenceTypeFile') },
  { value: 'TEAM' as const, label: t('corkboard.modal.referenceTypeTeam') },
  { value: 'ORGANIZATION' as const, label: t('corkboard.modal.referenceTypeOrganization') },
  { value: 'EVENT' as const, label: t('corkboard.modal.referenceTypeEvent') },
  { value: 'DOCUMENT' as const, label: t('corkboard.modal.referenceTypeDocument') },
  { value: 'URL' as const, label: t('corkboard.modal.referenceTypeUrl') },
])

const colorOptions = computed(() => [
  { value: 'WHITE' as const, label: t('corkboard.modal.colorWhite'), swatch: 'bg-surface-200' },
  { value: 'YELLOW' as const, label: t('corkboard.modal.colorYellow'), swatch: 'bg-yellow-400' },
  { value: 'RED' as const, label: t('corkboard.modal.colorRed'), swatch: 'bg-red-400' },
  { value: 'BLUE' as const, label: t('corkboard.modal.colorBlue'), swatch: 'bg-blue-400' },
  { value: 'GREEN' as const, label: t('corkboard.modal.colorGreen'), swatch: 'bg-green-400' },
  { value: 'PURPLE' as const, label: t('corkboard.modal.colorPurple'), swatch: 'bg-purple-400' },
  { value: 'GRAY' as const, label: t('corkboard.modal.colorGray'), swatch: 'bg-gray-400' },
])

// ----- visible (v-model 連携) -----

const dialogVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

// モーダルが開いた瞬間にフォームを初期化する
watch(
  () => props.visible,
  (v) => {
    if (v) {
      resetForm()
    }
  },
)

function resetForm() {
  errors.value = {}
  if (props.mode === 'edit' && props.card) {
    const c = props.card
    cardType.value = (c.cardType as CorkboardCardType) ?? 'MEMO'
    colorLabel.value = ((c.colorLabel as CorkboardColor) ?? 'WHITE')
    positionX.value = c.positionX ?? 0
    positionY.value = c.positionY ?? 0
    referenceType.value = ((c.referenceType as CorkboardReferenceType) ?? 'TIMELINE_POST')
    referenceId.value = c.referenceId ?? null
    referenceUrlPaste.value = ''
    referenceUrlPasteMessage.value = null
    title.value = c.title ?? ''
    body.value = c.body ?? ''
    url.value = c.url ?? ''
    userNote.value = c.userNote ?? ''
  } else {
    cardType.value = 'MEMO'
    colorLabel.value = 'WHITE'
    positionX.value = props.defaultPosition.x
    positionY.value = props.defaultPosition.y
    referenceType.value = 'TIMELINE_POST'
    referenceId.value = null
    referenceUrlPaste.value = ''
    referenceUrlPasteMessage.value = null
    title.value = ''
    body.value = ''
    url.value = ''
    userNote.value = ''
  }
}

// ----- バリデーション -----

/**
 * 簡易 URL 形式チェック。`URL` コンストラクタで例外なく構築できれば OK 。
 * バックエンド側でも `Size(max=2000)` のみで形式制約はないため、フロントで最低限ガードする。
 */
function isValidUrl(input: string): boolean {
  if (!input) return false
  try {
    const u = new URL(input)
    return u.protocol === 'http:' || u.protocol === 'https:'
  } catch {
    return false
  }
}

function validate(): boolean {
  const e: Record<string, string> = {}
  switch (cardType.value) {
    case 'REFERENCE':
      if (!referenceType.value) {
        e.referenceType = t('corkboard.validation.referenceTypeRequired')
      }
      if (referenceId.value == null || !Number.isFinite(referenceId.value) || referenceId.value <= 0) {
        e.referenceId = t('corkboard.validation.referenceIdRequired')
      }
      break
    case 'MEMO':
      if (!body.value.trim()) {
        e.body = t('corkboard.validation.memoBodyRequired')
      }
      break
    case 'URL':
      if (!url.value.trim()) {
        e.url = t('corkboard.validation.required')
      } else if (!isValidUrl(url.value.trim())) {
        e.url = t('corkboard.validation.urlInvalid')
      }
      break
    case 'SECTION_HEADER':
      if (!title.value.trim()) {
        e.title = t('corkboard.validation.sectionHeaderTitleRequired')
      }
      break
  }
  errors.value = e
  return Object.keys(e).length === 0
}

// ----- 保存 -----

/** 入力フィールドの空文字を null に正規化する。バックエンド DTO は null 許容。 */
function nullable(s: string): string | null {
  const t2 = s.trim()
  return t2.length === 0 ? null : t2
}

function buildCreatePayload(): CreateCardRequest {
  const base: CreateCardRequest = {
    cardType: cardType.value,
    colorLabel: colorLabel.value,
    positionX: positionX.value,
    positionY: positionY.value,
  }
  switch (cardType.value) {
    case 'REFERENCE':
      return {
        ...base,
        referenceType: referenceType.value,
        referenceId: referenceId.value,
        userNote: nullable(userNote.value),
      }
    case 'MEMO':
      return {
        ...base,
        title: nullable(title.value),
        body: nullable(body.value),
        userNote: nullable(userNote.value),
      }
    case 'URL':
      return {
        ...base,
        url: nullable(url.value),
        title: nullable(title.value),
        userNote: nullable(userNote.value),
      }
    case 'SECTION_HEADER':
      return {
        ...base,
        title: nullable(title.value),
      }
  }
}

function buildUpdatePayload(): UpdateCardRequest {
  // edit では cardType / referenceType / referenceId は不変。
  // 他フィールドは現在の入力値で部分更新する（空文字は null へ）。
  const payload: UpdateCardRequest = {
    colorLabel: colorLabel.value,
    positionX: positionX.value,
    positionY: positionY.value,
  }
  switch (cardType.value) {
    case 'REFERENCE':
      payload.userNote = nullable(userNote.value)
      break
    case 'MEMO':
      payload.title = nullable(title.value)
      payload.body = nullable(body.value)
      payload.userNote = nullable(userNote.value)
      break
    case 'URL':
      payload.url = nullable(url.value)
      payload.title = nullable(title.value)
      payload.userNote = nullable(userNote.value)
      break
    case 'SECTION_HEADER':
      payload.title = nullable(title.value)
      break
  }
  return payload
}

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    if (props.mode === 'create') {
      const res = await createCard(props.boardId, buildCreatePayload())
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.createSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    } else if (props.mode === 'edit' && props.card) {
      const res = await updateCard(props.boardId, props.card.id, buildUpdatePayload())
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.editSuccess'),
        life: 2500,
      })
      emit('save', res.data)
    }
    dialogVisible.value = false
  } catch (e) {
    captureQuiet(e, {
      context: `CardEditorModal: ${props.mode} 保存失敗`,
    })
    toast.add({
      severity: 'error',
      summary:
        props.mode === 'create'
          ? t('corkboard.toast.createError')
          : t('corkboard.toast.editError'),
      life: 3500,
    })
  } finally {
    saving.value = false
  }
}

function cancel() {
  emit('cancel')
  dialogVisible.value = false
}

// ----- F09.8 Phase G: 参照先入力ヒント / URL → ID 抽出補助 -----

/**
 * 参照種別ごとに「どこから ID を取るか」のヒント文言を返す。
 * i18n キーは `corkboard.referenceHint.*`。
 */
const referenceHintKey = computed<string>(() => {
  switch (referenceType.value) {
    case 'TIMELINE_POST':
      return 'corkboard.referenceHint.timelinePost'
    case 'BULLETIN_THREAD':
      return 'corkboard.referenceHint.bulletinThread'
    case 'BLOG_POST':
      return 'corkboard.referenceHint.blogPost'
    case 'CHAT_MESSAGE':
      return 'corkboard.referenceHint.chatMessage'
    case 'FILE':
      return 'corkboard.referenceHint.file'
    case 'TEAM':
      return 'corkboard.referenceHint.team'
    case 'ORGANIZATION':
      return 'corkboard.referenceHint.organization'
    case 'EVENT':
      return 'corkboard.referenceHint.event'
    case 'DOCUMENT':
      return 'corkboard.referenceHint.document'
    case 'URL':
      return 'corkboard.referenceHint.url'
    default:
      return 'corkboard.modal.referenceIdHint'
  }
})

/**
 * referenceType に応じた URL パスのヒント。
 * URL の末尾 / 連続する数字を対象に拾う簡易ロジックなので、
 * これを満たさない URL は手入力を促す。
 */
const REFERENCE_PATH_HINT: Partial<Record<CorkboardReferenceType, RegExp>> = {
  TIMELINE_POST: /\/timeline\/posts\/(\d+)/,
  BULLETIN_THREAD: /\/bulletin\/threads\/(\d+)/,
  BLOG_POST: /\/blog\/posts\/(\d+)/,
  CHAT_MESSAGE: /\/chat\/messages\/(\d+)/,
  FILE: /\/files\/(\d+)/,
  TEAM: /\/teams\/(\d+)/,
  ORGANIZATION: /\/organizations\/(\d+)/,
  EVENT: /\/events\/(\d+)/,
  DOCUMENT: /\/documents\/(\d+)/,
}

/**
 * URL から数値 ID を抽出する。
 *  1. referenceType ごとの専用パスで一致を試みる
 *  2. なければ「URL 末尾の連続数字」を抽出
 * いずれもダメなら null。
 */
function extractIdFromUrl(input: string, refType: CorkboardReferenceType): number | null {
  const trimmed = input.trim()
  if (!trimmed) return null
  const specific = REFERENCE_PATH_HINT[refType]
  if (specific) {
    const m = trimmed.match(specific)
    if (m && m[1]) {
      const v = Number(m[1])
      if (Number.isFinite(v) && v > 0) return v
    }
  }
  // 末尾連続数字（クエリ・ハッシュ除去後）
  const noQuery = trimmed.split(/[?#]/)[0] ?? trimmed
  const tail = noQuery.match(/(\d+)\/?$/)
  if (tail && tail[1]) {
    const v = Number(tail[1])
    if (Number.isFinite(v) && v > 0) return v
  }
  return null
}

/** 「URL から抽出」ボタン押下時 */
function applyReferenceUrlPaste() {
  const id = extractIdFromUrl(referenceUrlPaste.value, referenceType.value)
  if (id == null) {
    referenceUrlPasteMessage.value = {
      kind: 'error',
      text: t('corkboard.modal.referenceUrlExtractFailed'),
    }
    return
  }
  referenceId.value = id
  referenceUrlPasteMessage.value = {
    kind: 'success',
    text: t('corkboard.modal.referenceUrlExtractSuccess'),
  }
}

// ----- アクセシビリティ -----

const dialogHeader = computed(() =>
  props.mode === 'create'
    ? t('corkboard.modal.createTitle')
    : t('corkboard.modal.editTitle'),
)
</script>

<template>
  <Dialog
    v-model:visible="dialogVisible"
    modal
    :header="dialogHeader"
    :closable="!saving"
    :close-on-escape="!saving"
    :style="{ width: '560px', maxWidth: '95vw' }"
    :pt="{
      root: { 'aria-modal': 'true', 'data-testid': 'card-editor-modal' },
    }"
  >
    <form class="flex flex-col gap-4" data-testid="card-editor-form" @submit.prevent="save">
      <!-- カード種別（create のみ可変、edit では固定表示） -->
      <div class="flex flex-col gap-1">
        <label for="cardEditorType" class="text-sm font-medium">
          {{ t('corkboard.modal.cardType') }}
        </label>
        <Select
          v-if="props.mode === 'create'"
          id="cardEditorType"
          v-model="cardType"
          :options="cardTypeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          data-testid="card-editor-card-type-select"
        />
        <span
          v-else
          class="inline-flex items-center gap-2 rounded border border-surface-200 bg-surface-100 px-3 py-2 text-sm dark:border-surface-700 dark:bg-surface-800"
        >
          {{
            cardTypeOptions.find((o) => o.value === cardType)?.label ?? cardType
          }}
        </span>
      </div>

      <!-- REFERENCE: 参照先種別 + 参照先 ID -->
      <template v-if="cardType === 'REFERENCE'">
        <div class="flex flex-col gap-1">
          <label for="cardEditorRefType" class="text-sm font-medium">
            {{ t('corkboard.modal.referenceType') }}
          </label>
          <Select
            id="cardEditorRefType"
            v-model="referenceType"
            :options="referenceTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            :disabled="props.mode === 'edit'"
            data-testid="card-editor-reference-type-select"
          />
          <small v-if="errors.referenceType" class="text-red-500">
            {{ errors.referenceType }}
          </small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorRefId" class="text-sm font-medium">
            {{ t('corkboard.modal.referenceId') }}
          </label>
          <InputNumber
            id="cardEditorRefId"
            v-model="referenceId"
            :min="1"
            :use-grouping="false"
            class="w-full"
            :disabled="props.mode === 'edit'"
            data-testid="card-editor-reference-id-input"
          />
          <!-- F09.8 Phase G: 参照種別ごとのヒント -->
          <small class="text-xs text-surface-500">
            {{ t(referenceHintKey) }}
          </small>
          <small v-if="errors.referenceId" class="text-red-500">
            {{ errors.referenceId }}
          </small>
        </div>

        <!-- F09.8 Phase G: URL ペーストで ID を自動抽出する補助欄（create 時のみ） -->
        <div
          v-if="props.mode === 'create' && referenceType !== 'URL'"
          class="flex flex-col gap-1 rounded border border-dashed border-surface-300 p-2 dark:border-surface-700"
        >
          <label for="cardEditorRefUrlPaste" class="text-xs font-medium text-surface-600 dark:text-surface-300">
            {{ t('corkboard.modal.referenceUrlPaste') }}
          </label>
          <div class="flex gap-2">
            <InputText
              id="cardEditorRefUrlPaste"
              v-model="referenceUrlPaste"
              type="url"
              :placeholder="t('corkboard.modal.referenceUrlPastePlaceholder')"
              class="flex-1"
              @keydown.enter.prevent="applyReferenceUrlPaste"
            />
            <Button
              :label="t('corkboard.modal.referenceUrlPaste')"
              icon="pi pi-arrow-right"
              size="small"
              severity="secondary"
              :disabled="!referenceUrlPaste.trim()"
              @click="applyReferenceUrlPaste"
            />
          </div>
          <small class="text-[11px] text-surface-500">
            {{ t('corkboard.modal.referenceIdPasteHint') }}
          </small>
          <small
            v-if="referenceUrlPasteMessage"
            :class="
              referenceUrlPasteMessage.kind === 'success'
                ? 'text-green-600 dark:text-green-400'
                : 'text-red-500'
            "
            class="text-[11px]"
            role="status"
          >
            {{ referenceUrlPasteMessage.text }}
          </small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorRefNote" class="text-sm font-medium">
            {{ t('corkboard.modal.userNote') }}
          </label>
          <Textarea
            id="cardEditorRefNote"
            v-model="userNote"
            :placeholder="t('corkboard.modal.userNotePlaceholder')"
            rows="3"
            auto-resize
            class="w-full"
          />
        </div>
      </template>

      <!-- MEMO: title (任意) + body (必須) + userNote -->
      <template v-else-if="cardType === 'MEMO'">
        <div class="flex flex-col gap-1">
          <label for="cardEditorMemoTitle" class="text-sm font-medium">
            {{ t('corkboard.modal.titleOptional') }}
          </label>
          <InputText
            id="cardEditorMemoTitle"
            v-model="title"
            class="w-full"
            data-testid="card-editor-title-input"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorMemoBody" class="text-sm font-medium">
            {{ t('corkboard.modal.body') }}
          </label>
          <Textarea
            id="cardEditorMemoBody"
            v-model="body"
            :placeholder="t('corkboard.modal.bodyPlaceholder')"
            rows="4"
            auto-resize
            class="w-full"
            data-testid="card-editor-body-input"
          />
          <small v-if="errors.body" class="text-red-500">{{ errors.body }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorMemoNote" class="text-sm font-medium">
            {{ t('corkboard.modal.userNote') }}
          </label>
          <Textarea
            id="cardEditorMemoNote"
            v-model="userNote"
            :placeholder="t('corkboard.modal.userNotePlaceholder')"
            rows="2"
            auto-resize
            class="w-full"
          />
        </div>
      </template>

      <!-- URL: url (必須) + title (任意) + userNote -->
      <template v-else-if="cardType === 'URL'">
        <div class="flex flex-col gap-1">
          <label for="cardEditorUrl" class="text-sm font-medium">
            {{ t('corkboard.modal.url') }}
          </label>
          <InputText
            id="cardEditorUrl"
            v-model="url"
            type="url"
            :placeholder="t('corkboard.modal.urlPlaceholder')"
            class="w-full"
            data-testid="card-editor-url-input"
          />
          <small v-if="errors.url" class="text-red-500">{{ errors.url }}</small>
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorUrlTitle" class="text-sm font-medium">
            {{ t('corkboard.modal.titleOptional') }}
          </label>
          <InputText
            id="cardEditorUrlTitle"
            v-model="title"
            class="w-full"
            data-testid="card-editor-title-input"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label for="cardEditorUrlNote" class="text-sm font-medium">
            {{ t('corkboard.modal.userNote') }}
          </label>
          <Textarea
            id="cardEditorUrlNote"
            v-model="userNote"
            :placeholder="t('corkboard.modal.userNotePlaceholder')"
            rows="2"
            auto-resize
            class="w-full"
          />
        </div>
      </template>

      <!-- SECTION_HEADER: title (必須) -->
      <template v-else-if="cardType === 'SECTION_HEADER'">
        <div class="flex flex-col gap-1">
          <label for="cardEditorSectionTitle" class="text-sm font-medium">
            {{ t('corkboard.modal.title') }}
          </label>
          <InputText
            id="cardEditorSectionTitle"
            v-model="title"
            class="w-full"
            data-testid="card-editor-title-input"
          />
          <small class="text-xs text-surface-500">
            {{ t('corkboard.modal.titleSectionHint') }}
          </small>
          <small v-if="errors.title" class="text-red-500">{{ errors.title }}</small>
        </div>
      </template>

      <!-- カラーラベル -->
      <div class="flex flex-col gap-1">
        <span class="text-sm font-medium">{{ t('corkboard.modal.colorLabel') }}</span>
        <div role="radiogroup" :aria-label="t('corkboard.modal.colorLabel')" class="flex flex-wrap gap-2">
          <button
            v-for="opt in colorOptions"
            :key="opt.value"
            type="button"
            role="radio"
            :aria-checked="colorLabel === opt.value"
            :aria-label="opt.label"
            :data-testid="`card-editor-color-label-${opt.value}`"
            class="flex h-8 w-8 items-center justify-center rounded-full border-2 transition-all"
            :class="[
              opt.swatch,
              colorLabel === opt.value
                ? 'border-primary scale-110'
                : 'border-surface-300 dark:border-surface-600 hover:scale-105',
            ]"
            @click="colorLabel = opt.value"
          >
            <i
              v-if="colorLabel === opt.value"
              class="pi pi-check text-[10px] text-surface-900 dark:text-surface-50"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <!-- 位置 -->
      <fieldset class="flex flex-col gap-1">
        <legend class="text-sm font-medium">{{ t('corkboard.modal.position') }}</legend>
        <div class="grid grid-cols-2 gap-2">
          <div class="flex flex-col gap-1">
            <label for="cardEditorPosX" class="text-xs text-surface-500">
              {{ t('corkboard.modal.positionX') }}
            </label>
            <InputNumber
              id="cardEditorPosX"
              v-model="positionX"
              :min="0"
              :use-grouping="false"
              class="w-full"
            />
          </div>
          <div class="flex flex-col gap-1">
            <label for="cardEditorPosY" class="text-xs text-surface-500">
              {{ t('corkboard.modal.positionY') }}
            </label>
            <InputNumber
              id="cardEditorPosY"
              v-model="positionY"
              :min="0"
              :use-grouping="false"
              class="w-full"
            />
          </div>
        </div>
      </fieldset>
    </form>

    <template #footer>
      <Button
        :label="t('corkboard.modal.cancel')"
        severity="secondary"
        text
        :disabled="saving"
        data-testid="card-editor-cancel-button"
        @click="cancel"
      />
      <Button
        :label="t('corkboard.modal.save')"
        icon="pi pi-check"
        :loading="saving"
        data-testid="card-editor-save-button"
        @click="save"
      />
    </template>
  </Dialog>
</template>
