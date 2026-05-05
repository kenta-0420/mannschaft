import type {
  CorkboardCardDetail,
  CorkboardCardType,
  CorkboardColor,
  CorkboardReferenceType,
  CreateCardRequest,
  UpdateCardRequest,
} from '~/types/corkboard'

/**
 * F09.8 Phase 4-β: CardEditorModal のフォームロジックを抽出した composable。
 *
 * 責務:
 *  - フォーム状態管理（cardType / colorLabel / positionX,Y / referenceType / referenceId /
 *    title / body / url / userNote / errors / saving）
 *  - フォーム初期化（create / edit モードで初期値が異なる）
 *  - バリデーション（cardType 別の必須フィールド判定・URL 形式チェック）
 *  - ペイロード構築（buildCreatePayload / buildUpdatePayload）
 *  - URL から参照 ID 抽出補助（extractIdFromUrl）
 *  - カード種別・色・参照種別オプション配列生成（i18n 連携）
 *
 * CardEditorModal.vue はこの composable を利用して表示ロジックに集中する。
 */

// ===== 定数 =====

/**
 * referenceType ごとの URL パスパターン。
 * URL の末尾連続数字で ID を抽出するフォールバックより優先して適用される。
 */
export const REFERENCE_PATH_HINT: Partial<Record<CorkboardReferenceType, RegExp>> = {
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

// ===== 型定義 =====

export interface DefaultPosition {
  x: number
  y: number
}

// ===== composable =====

export function useCardEditorForm(
  mode: Ref<'create' | 'edit'>,
  card: Ref<CorkboardCardDetail | null>,
  _boardId: Ref<number>,
  defaultPosition: Ref<DefaultPosition> = ref({ x: 0, y: 0 }),
  /**
   * i18n 翻訳関数。デフォルトは `useI18n().t` を使用する。
   * テスト環境では setup コンテキスト外で `useI18n` が呼べないため、
   * ダミーの t 関数を注入して利用すること。
   */
  tFn?: (key: string) => string,
) {
  // tFn が渡されない場合は useI18n() から取得（実際のコンポーネント利用時）
  const { t } = tFn ? { t: tFn } : useI18n()

  // ----- フォーム状態 -----

  const cardType = ref<CorkboardCardType>('MEMO')
  const colorLabel = ref<CorkboardColor>('WHITE')
  const positionX = ref<number>(0)
  const positionY = ref<number>(0)

  // REFERENCE 用
  const referenceType = ref<CorkboardReferenceType>('TIMELINE_POST')
  const referenceId = ref<number | null>(null)
  /** URL ペースト欄の一時入力値（送信対象外） */
  const referenceUrlPaste = ref<string>('')
  /** URL 抽出結果メッセージ（成功 / 失敗） */
  const referenceUrlPasteMessage = ref<{ kind: 'success' | 'error'; text: string } | null>(null)

  // MEMO / URL / SECTION_HEADER 共通
  const title = ref<string>('')
  const body = ref<string>('')
  const url = ref<string>('')
  const userNote = ref<string>('')

  const saving = ref(false)
  const errors = ref<Record<string, string>>({})

  // ----- オプション配列（i18n） -----

  const cardTypeOptions = computed(() => [
    { value: 'REFERENCE' as const, label: t('corkboard.modal.cardTypeReference') },
    { value: 'MEMO' as const, label: t('corkboard.modal.cardTypeMemo') },
    { value: 'URL' as const, label: t('corkboard.modal.cardTypeUrl') },
    { value: 'SECTION_HEADER' as const, label: t('corkboard.modal.cardTypeSectionHeader') },
  ])

  const referenceTypeOptions = computed(() => [
    { value: 'TIMELINE_POST' as const, label: t('corkboard.modal.referenceTypeTimelinePost') },
    {
      value: 'BULLETIN_THREAD' as const,
      label: t('corkboard.modal.referenceTypeBulletinThread'),
    },
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
    {
      value: 'WHITE' as const,
      label: t('corkboard.modal.colorWhite'),
      swatch: 'bg-surface-200',
    },
    {
      value: 'YELLOW' as const,
      label: t('corkboard.modal.colorYellow'),
      swatch: 'bg-yellow-400',
    },
    { value: 'RED' as const, label: t('corkboard.modal.colorRed'), swatch: 'bg-red-400' },
    { value: 'BLUE' as const, label: t('corkboard.modal.colorBlue'), swatch: 'bg-blue-400' },
    { value: 'GREEN' as const, label: t('corkboard.modal.colorGreen'), swatch: 'bg-green-400' },
    {
      value: 'PURPLE' as const,
      label: t('corkboard.modal.colorPurple'),
      swatch: 'bg-purple-400',
    },
    { value: 'GRAY' as const, label: t('corkboard.modal.colorGray'), swatch: 'bg-gray-400' },
  ])

  // ----- フォーム初期化 -----

  function resetForm() {
    errors.value = {}
    if (mode.value === 'edit' && card.value) {
      const c = card.value
      cardType.value = (c.cardType as CorkboardCardType) ?? 'MEMO'
      colorLabel.value = (c.colorLabel as CorkboardColor) ?? 'WHITE'
      positionX.value = c.positionX ?? 0
      positionY.value = c.positionY ?? 0
      referenceType.value = (c.referenceType as CorkboardReferenceType) ?? 'TIMELINE_POST'
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
      positionX.value = defaultPosition.value.x
      positionY.value = defaultPosition.value.y
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
   * 簡易 URL 形式チェック。
   * バックエンド側は `Size(max=2000)` のみで形式制約はないため、フロントで最低限ガードする。
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
        if (
          referenceId.value == null ||
          !Number.isFinite(referenceId.value) ||
          referenceId.value <= 0
        ) {
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

  // ----- ペイロード構築 -----

  /** 入力フィールドの空文字を null に正規化する。バックエンド DTO は null 許容。 */
  function nullable(s: string): string | null {
    const trimmed = s.trim()
    return trimmed.length === 0 ? null : trimmed
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

  // ----- URL から ID 抽出補助 -----

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

  // ----- モード / カード変更時の自動リセット -----

  watch([mode, card], resetForm, { immediate: true })

  // ----- 公開インターフェース -----

  return {
    // 状態
    cardType,
    colorLabel,
    positionX,
    positionY,
    referenceType,
    referenceId,
    referenceUrlPaste,
    referenceUrlPasteMessage,
    title,
    body,
    url,
    userNote,
    errors,
    saving,
    // オプション
    cardTypeOptions,
    referenceTypeOptions,
    colorOptions,
    // 操作
    resetForm,
    validate,
    buildCreatePayload,
    buildUpdatePayload,
    extractIdFromUrl,
    applyReferenceUrlPaste,
    // ユーティリティ（テスト・外部用）
    isValidUrl,
  }
}
