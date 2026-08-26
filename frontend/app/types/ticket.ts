export type TicketBookStatus = 'PENDING' | 'ACTIVE' | 'EXHAUSTED' | 'EXPIRED' | 'CANCELLED'
export type TicketPaymentMethod = 'STRIPE' | 'CASH' | 'CARD_ON_SITE' | 'E_MONEY' | 'OTHER'

// === 回数券商品（ネストDTO構造 / BE #1174 追従）===
export interface ProductMetaDto {
  name: string
  description: string | null
  totalTickets: number
  sortOrder: number
}

export interface ProductPricingDto {
  price: number
  priceExcludingTax: number
  taxRate: number
  validityDays: number
}

export interface StripeIntegrationDto {
  stripeProductId: string | null
  stripePriceId: string | null
}

export interface ProductDisplayDto {
  imageUrl: string | null
  isOnlinePurchasable: boolean
  isActive: boolean
}

export interface ProductAuditDto {
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface TicketProductResponse {
  id: number
  meta: ProductMetaDto
  pricing: ProductPricingDto
  stripe: StripeIntegrationDto
  display: ProductDisplayDto
  audit: ProductAuditDto
}

// === 回数券ブック（ネストDTO構造 / BE #1174 追従）===
export interface TicketQuantityDto {
  totalTickets: number
  usedTickets: number
  remainingTickets: number
}

export interface TicketStatusDto {
  status: TicketBookStatus
  purchasedAt: string
  expiresAt: string | null
  daysUntilExpiry: number | null
}

export interface NoteDto {
  note: string | null
}

export interface BookAuditDto {
  createdAt: string
  updatedAt: string
}

export interface TicketBookResponse {
  id: number
  userId: number
  userName: string
  productName: string
  quantity: TicketQuantityDto
  status: TicketStatusDto
  note: NoteDto
  audit: BookAuditDto
}

// === 回数券ブック詳細（ネストDTO構造 / BE #1174 追従）===
export interface PaymentSummary {
  paymentMethod: TicketPaymentMethod
  amount: number
  status: string
  paidAt: string | null
}

export interface DetailAuditDto {
  note: string | null
  createdAt: string
  updatedAt: string
}

export interface TicketBookDetailResponse {
  id: number
  productName: string
  quantity: TicketQuantityDto
  status: TicketStatusDto
  payment: PaymentSummary | null
  consumptions: TicketConsumption[]
  audit: DetailAuditDto
}

export interface TicketConsumption {
  id: number
  bookId: number
  consumedBy: { id: number; displayName: string }
  consumedAt: string
  note: string | null
  isVoided: boolean
}

export interface TicketStats {
  totalSold: number
  totalRevenue: number
  activeBooks: number
  avgDaysToExhaust: number
  expiryRate: number
  byProduct: Array<{
    productId: number
    productName: string
    soldCount: number
    revenue: number
    activeCount: number
  }>
}

export interface TicketWidgetData {
  activeCount: number
  urgencyLevel: 'NORMAL' | 'WARNING' | 'CRITICAL'
  nearestExpiry: string | null
  daysUntilExpiry: number | null
}
