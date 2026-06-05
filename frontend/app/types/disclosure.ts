/**
 * 重要事項説明書（参考） — F09.14 型定義
 *
 * バックエンド DTO（Phase 2-β-4 で確定）:
 *  - DisclosureFormTemplateResponse
 *  - DisclosureFormDraftResponse / DisclosureFormDraftRequest
 *  - DisclosureExportResponse
 *
 * 設計書: docs/features/F09.14_property_disclosure.md
 */

/** ドラフトのステータス。 */
export type DraftStatus = 'DRAFT' | 'READY' | 'EXPORTED'

/** 出力フォーマット。WORD は Phase 3 以降の予定。 */
export type DisclosureOutputFormat = 'PDF' | 'EXCEL' | 'WORD'

/** スコープ種別。テンプレートとドラフトで使用。 */
export type DisclosureScopeType = 'ORGANIZATION'

/** form_schema 内の各フィールド型。 */
export type DisclosureFieldType =
  | 'TEXT'
  | 'NUMBER'
  | 'DATE'
  | 'SELECT'
  | 'MULTISELECT'
  | 'CHECKBOX'
  | 'TEXTAREA'
  | 'AUTO_TABLE'
  | 'AUTO_FIELD'

/** SELECT/MULTISELECT 選択肢。 */
export interface DisclosureFieldOption {
  value: string
  label: string
}

/** form_schema 内のフィールド定義。 */
export interface DisclosureFormField {
  id: string
  label: string
  type: DisclosureFieldType
  required?: boolean
  maxLength?: number
  /** AUTO_FIELD/AUTO_TABLE で自動引用するソース名（例: "DwellingUnitOwner"）。 */
  autoFillFrom?: string
  /** 自動引用時のフィルタ条件。 */
  autoFillFilter?: Record<string, unknown>
  /** AUTO_TABLE 用の列ラベル配列。 */
  columns?: string[]
  /** SELECT/MULTISELECT の選択肢。 */
  options?: DisclosureFieldOption[]
  /** 補足説明（プレースホルダ等）。 */
  hint?: string
}

/** form_schema のセクション定義。 */
export interface DisclosureFormSection {
  id: string
  title: string
  fields: DisclosureFormField[]
}

/** 重説書の form_schema（テンプレートに紐付く）。 */
export interface FormSchema {
  sections: DisclosureFormSection[]
}

/** 様式テンプレート（システム提供 or 組織カスタム）。 */
export interface DisclosureFormTemplate {
  id: number
  code: string
  name: string
  /** JIS 都道府県コード。NULL=全国共通。 */
  prefectureCode: string | null
  version: string
  isStandard: boolean
  isSystemTemplate: boolean
  scopeType: DisclosureScopeType | null
  scopeId: string | null
  formSchema: FormSchema
  effectiveFrom: string | null
  effectiveUntil: string | null
  isActive: boolean
  createdAt: string
  updatedAt: string
}

/** 入力済みドラフト。 */
export interface DisclosureFormDraft {
  id: number
  scopeType: DisclosureScopeType
  scopeId: string
  templateId: number
  templateVersionSnapshot: string
  title: string
  targetDwellingUnitId: number | null
  formData: Record<string, unknown>
  referencedPackageIds: number[] | null
  status: DraftStatus
  createdBy: number
  createdAt: string
  updatedAt: string
  /** 楽観的ロック用 version。PUT 時に必須。 */
  version: number
}

/** ドラフト作成・更新リクエスト。 */
export interface DisclosureFormDraftRequest {
  templateId: number
  title: string
  targetDwellingUnitId?: number | null
  formData?: Record<string, unknown>
  /** PUT 時に必須（楽観的ロック）。POST 時は省略可。 */
  version?: number
}

/** 出力履歴。出力直後・ダウンロード時のみ downloadUrl が付与される。 */
export interface DisclosureExport {
  id: number
  scopeId: string
  draftId: number | null
  templateCodeSnapshot: string
  templateVersionSnapshot: string
  outputFormat: DisclosureOutputFormat
  sharedFileId: number
  targetDwellingUnitId: number | null
  recipientNote: string | null
  sha256: string
  expiresAt: string | null
  createdAt: string
  /** 出力直後 / ダウンロードリクエスト時のみ。 */
  downloadUrl?: string
  downloadUrlExpiresAt?: string
  /** 出力直後のみ。バリデーション/可視性除外などの注意メッセージ。 */
  warnings?: string[]
}

/** ドラフト一覧フィルタ。 */
export interface DisclosureDraftListFilter {
  status?: DraftStatus | null
  templateId?: number | null
  page?: number
  size?: number
}

/** 出力履歴一覧フィルタ。 */
export interface DisclosureExportListFilter {
  outputFormat?: DisclosureOutputFormat | null
  page?: number
  size?: number
}

/**
 * 出力履歴の自動削除予定日を延長するリクエスト（F09.14 Phase 3-E / 4-B）。
 *
 * バックエンド DTO: {@code com.mannschaft.app.disclosure.dto.ExtendExpiryRequest}
 *  - newExpiresAt: ISO-8601 形式の LocalDateTime 文字列（例: "2026-12-31T23:59:00"）。
 *    秒以下まで含めて送信し、バックエンド側で本日から 7 年以内かどうかを再検証する。
 */
export interface ExtendExpiryRequest {
  newExpiresAt: string
}

/**
 * 出力履歴の自動削除予定日延長レスポンス。
 * 更新後の {@link DisclosureExport} を返す（download URL は付与されない）。
 */
export type ExtendExpiryResponse = DisclosureExport

/** 一覧 API のページングメタ情報。 */
export interface DisclosureListMeta {
  total: number
  page: number
  size: number
  totalPages: number
}
