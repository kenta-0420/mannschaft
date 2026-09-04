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

export interface ReceiptResponse {
  id: number
  receiptNumber: string
  recipientName: string
  recipientAddress: string | null
  totalAmount: number
  taxAmount: number
  description: string
  status: ReceiptStatus
  issuedAt: string | null
  voidedAt: string | null
  voidedReason: string | null
  pdfUrl: string | null
  lineItems: ReceiptLineItem[]
  paymentId: number | null
  createdAt: string
}

export interface ReceiptLineItem {
  id: number
  description: string
  quantity: number
  unitPrice: number
  amount: number
  taxRate: number
  taxAmount: number
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
