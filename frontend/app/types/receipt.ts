export type ReceiptStatus = 'DRAFT' | 'ISSUED'

/**
 * 領収書発行者設定（F08.4 D-2）。
 * `IssuerSettingsResponse.java` / `UpdateIssuerSettingsRequest.java` の実フィールドに合わせる
 * （生成型 `types/generated/index.ts` の `IssuerSettingsResponse` を正本として突き合わせ済み）。
 */
export interface ReceiptIssuerSettings {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  issuerName: string
  postalCode: string | null
  address: string | null
  phone: string | null
  isQualifiedInvoicer: boolean
  invoiceRegistrationNumber: string | null
  defaultSealUserId: number | null
  defaultSealVariant: string | null
  receiptNoteTemplate: string | null
  receiptNumberPrefix: string | null
  fiscalYearStartMonth: number
  autoResetNumber: boolean
  customFooter: string | null
  /** ストレージキー。URL 組み立てには使わない（表示は logoUrl を使う。D-8） */
  logoStorageKey: string | null
  /** 署名付き GET URL。未設定なら null（D-8） */
  logoUrl: string | null
  nextReceiptNumber: number
  createdAt: string
  updatedAt: string
}

/**
 * 領収書明細行。BE `ReceiptResponse.LineItemResponse` の実フィールドに一致させる。
 * quantity / unitPrice は BE に存在しない（金額は行ごとの amount で持つ）。
 */
export interface ReceiptLineItem {
  id: number
  description: string
  amount: number
  taxRate: number
  taxAmount: number
  amountExclTax: number
}

/** 発行者。BE `ReceiptResponse.IssuedByResponse`。 */
export interface ReceiptIssuedBy {
  id: number
  displayName: string
}

/** 発行時警告。BE `ReceiptResponse.WarningResponse`。 */
export interface ReceiptWarning {
  code: string
  message: string
}

/**
 * 領収書レスポンス（運営・管理者向け）。
 * BE `receipt/dto/ReceiptResponse.java` の実フィールドと 1:1 で対応させる。
 *
 * 金額は `amount`（BE のフィールド名）である。過去にここが `totalAmount` と宣言されていたため、
 * BE が返さないフィールドを「必ずある number」と型が偽り、一覧の金額列が全行で
 * `Cannot read properties of undefined` を投げていた（CMP-260907-0915）。
 */
export interface ReceiptResponse {
  id: number
  /** DRAFT の間は未採番のため null。 */
  receiptNumber: string | null
  status: ReceiptStatus
  recipientName: string
  recipientPostalCode: string | null
  recipientAddress: string | null
  issuerName: string
  issuerPostalCode: string | null
  issuerAddress: string | null
  issuerPhone: string | null
  isQualifiedInvoice: boolean
  invoiceRegistrationNumber: string | null
  description: string
  /** 税込金額。 */
  amount: number
  taxRate: number
  taxAmount: number
  amountExclTax: number
  lineItems: ReceiptLineItem[]
  paymentMethodLabel: string | null
  /** ISO 日付（yyyy-MM-dd）。 */
  paymentDate: string | null
  issuedAt: string | null
  issuedBy: ReceiptIssuedBy | null
  sealStamped: boolean | null
  sealStampLogId: number | null
  pdfStatus: string | null
  pdfDownloadUrl: string | null
  memberPaymentId: number | null
  scheduleId: number | null
  isVoided: boolean | null
  voidedAt: string | null
  voidedBy: number | null
  voidedReason: string | null
  warnings: ReceiptWarning[] | null
}

/**
 * マイページ用領収書レスポンス。BE `receipt/dto/MyReceiptResponse.java`。
 * 管理者向け `ReceiptResponse` とは別形なので混同しないこと。
 */
export interface MyReceiptResponse {
  id: number
  receiptNumber: string | null
  scopeName: string | null
  description: string
  amount: number
  isQualifiedInvoice: boolean | null
  paymentDate: string | null
  issuedAt: string | null
  isVoided: boolean | null
  pdfDownloadUrl: string | null
}

/**
 * 領収書発行リクエスト。BE `receipt/dto/CreateReceiptRequest.java` の実フィールド名に合わせる。
 * 金額は `amount`（`totalAmount` ではない）。BE は `amount` に `@NotNull` を持つ。
 */
export interface IssueReceiptRequest {
  presetId?: number
  status?: ReceiptStatus
  memberPaymentId?: number
  recipientUserId?: number
  recipientName?: string
  recipientPostalCode?: string
  recipientAddress?: string
  description?: string
  /** 税込金額。BE 必須。 */
  amount: number
  taxRate?: number
  lineItems?: IssueReceiptLineItemRequest[]
  paymentMethodLabel?: string
  paymentDate?: string
  sealStamp?: boolean
  scheduleId?: number
  emailDelivery?: { enabled?: boolean, email?: string }
}

/** 発行リクエストの明細行。BE `CreateReceiptRequest.LineItemRequest`。 */
export interface IssueReceiptLineItemRequest {
  description: string
  amount: number
  taxRate: number
}

export interface ReceiptPreset {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: string
  name: string
  descriptionTemplate: string
  lineItemsTemplate: string
  createdAt: string
}
