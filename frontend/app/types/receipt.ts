export type ReceiptStatus = 'DRAFT' | 'ISSUED'

export interface ReceiptIssuerSettings {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  issuerName: string
  registrationNumber: string | null
  address: string | null
  logoUrl: string | null
  footerText: string | null
  fiscalYearStart: number
  defaultSealVariant: string | null
}

export interface ReceiptResponse {
  id: number
  receiptNumber: string
  issuerSettings: ReceiptIssuerSettings
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
  scopeId: number
  name: string
  descriptionTemplate: string
  lineItemsTemplate: string
  createdAt: string
}
