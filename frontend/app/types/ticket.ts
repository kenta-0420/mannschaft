export type TicketBookStatus = 'PENDING' | 'ACTIVE' | 'EXHAUSTED' | 'EXPIRED' | 'CANCELLED'
export type TicketPaymentMethod = 'STRIPE' | 'CASH' | 'CARD_ON_SITE' | 'E_MONEY' | 'OTHER'

export interface TicketProductResponse {
  id: number
  teamId: number
  name: string
  description: string | null
  totalTickets: number
  price: number
  taxRate: number
  validityDays: number
  isOnlinePurchaseEnabled: boolean
  isActive: boolean
  createdAt: string
}

export interface TicketBookResponse {
  id: number
  productId: number
  productName: string
  userId: number
  displayName: string
  avatarUrl: string | null
  status: TicketBookStatus
  totalTickets: number
  remainingTickets: number
  expiresAt: string | null
  purchasedAt: string
  paymentMethod: TicketPaymentMethod
  createdAt: string
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
