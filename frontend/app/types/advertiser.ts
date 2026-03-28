// === Enums ===

export type AdvertiserAccountStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED'
export type BillingMethod = 'STRIPE' | 'INVOICE'
export type PricingModel = 'CPM' | 'CPC'
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'OVERDUE'
export type CreditLimitRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type ReportFrequency = 'WEEKLY' | 'MONTHLY'
export type CampaignStatus = 'DRAFT' | 'PENDING_REVIEW' | 'ACTIVE' | 'PAUSED' | 'ENDED'

// === Account ===

export interface AdvertiserAccountResponse {
  id: number
  organizationId: number
  status: AdvertiserAccountStatus
  companyName: string
  contactEmail: string
  billingMethod: BillingMethod
  creditLimit: number
  approvedAt: string | null
  createdAt: string
}

export interface AdvertiserAccountDetailResponse {
  id: number
  organizationId: number
  organizationName: string
  status: AdvertiserAccountStatus
  companyName: string
  contactEmail: string
  billingMethod: BillingMethod
  creditLimit: number
  approvedAt: string | null
  createdAt: string
}

export interface RegisterAdvertiserRequest {
  companyName: string
  contactEmail: string
  billingMethod: BillingMethod
}

export interface UpdateAdvertiserAccountRequest {
  companyName?: string
  contactEmail?: string
}

// === Rate Cards ===

export interface AdRateCardResponse {
  id: number
  targetPrefecture: string | null
  targetTemplate: string | null
  pricingModel: PricingModel
  unitPrice: number
  minDailyBudget: number
  effectiveFrom: string
  effectiveUntil: string | null
  createdBy: number
  createdAt: string
}

export interface PublicRateCardResponse {
  targetPrefecture: string | null
  targetTemplate: string | null
  pricingModel: PricingModel
  unitPrice: number
  minDailyBudget: number
  label: string
}

export interface CreateAdRateCardRequest {
  targetPrefecture?: string
  targetTemplate?: string
  pricingModel: PricingModel
  unitPrice: number
  minDailyBudget: number
  effectiveFrom: string
}

// === Rate Simulator ===

export interface RateSimulatorResponse {
  input: {
    prefecture: string | null
    template: string | null
    pricingModel: PricingModel
    impressions: number | null
    clicks: number | null
    days: number | null
  }
  rateCard: {
    unitPrice: number
    unitLabel: string
    minDailyBudget: number
    effectiveFrom: string
  }
  estimate: {
    totalCost: number
    taxAmount: number
    totalWithTax: number
    dailyCost: number
    dailyImpressions: number | null
    estimatedClicks: number | null
    estimatedCtr: number | null
    reachMin: number | null
    reachMax: number | null
    reachLabel: string | null
  }
  comparison: RateComparisonItem[]
}

export interface RateComparisonItem {
  prefecture: string | null
  template: string | null
  unitPrice: number
  totalCost: number
  label: string
}

// === Overview ===

export interface AdvertiserOverviewResponse {
  period: { from: string; to: string }
  totalCampaigns: number
  activeCampaigns: number
  totalImpressions: number
  totalClicks: number
  avgCtr: number
  totalCost: number
  monthlyBudgetUsedPct: number
  creditLimit: number
  campaigns: CampaignSummary[]
}

export interface CampaignSummary {
  campaignId: number
  campaignName: string
  status: string
  impressions: number
  clicks: number
  ctr: number
  cost: number
}

// === Performance ===

export interface CampaignPerformanceResponse {
  campaignId: number
  campaignName: string
  status: string
  pricingModel: string
  summary: {
    totalImpressions: number
    totalClicks: number
    avgCtr: number
    totalCost: number
    avgCpm: number | null
    avgCpc: number | null
    conversions: number | null
    conversionRate: number | null
    costPerConversion: number | null
  }
  benchmark: {
    platformAvgCtr: number | null
    yourCtrPercentile: number | null
    sameTemplateAvgCtr: number | null
    sameTemplateAvgCpc: number | null
  } | null
  points: {
    period: string
    impressions: number
    clicks: number
    ctr: number
    cost: number
    conversions: number | null
  }[]
}

export interface CreativeComparisonResponse {
  campaignId: number
  creatives: {
    adId: number
    title: string
    impressions: number
    clicks: number
    ctr: number
    cost: number
    conversionRank: number | null
  }[]
  winner: {
    adId: number
    reason: string
  } | null
}

export interface BreakdownResponse {
  campaignId: number
  breakdownBy: string
  items: {
    prefecture: string | null
    template: string | null
    impressions: number
    clicks: number
    ctr: number
    cost: number
    unitPrice: number | null
  }[]
}

// === Invoices ===

export interface InvoiceSummaryResponse {
  id: number
  invoiceNumber: string
  invoiceMonth: string
  totalAmount: number
  taxAmount: number
  totalWithTax: number
  status: InvoiceStatus
  issuedAt: string | null
  dueDate: string | null
}

export interface InvoiceDetailResponse {
  id: number
  invoiceNumber: string
  invoiceMonth: string
  totalAmount: number
  taxRate: number
  taxAmount: number
  totalWithTax: number
  status: InvoiceStatus
  issuedAt: string | null
  dueDate: string | null
  note: string | null
  items: InvoiceItemResponse[]
}

export interface InvoiceItemResponse {
  campaignId: number
  campaignName: string
  pricingModel: PricingModel
  impressions: number
  clicks: number
  unitPrice: number
  subtotal: number
}

export interface MarkInvoicePaidRequest {
  paidAt: string
  note?: string
}

// === Report Schedules ===

export interface ReportScheduleResponse {
  id: number
  frequency: ReportFrequency
  recipients: string[]
  includeCampaigns: number[] | null
  enabled: boolean
  lastSentAt: string | null
}

export interface CreateReportScheduleRequest {
  frequency: ReportFrequency
  recipients: string[]
  includeCampaigns?: number[]
}

// === Credit Limit Requests ===

export interface CreditLimitRequestResponse {
  id: number
  currentLimit: number
  requestedLimit: number
  reason: string
  status: CreditLimitRequestStatus
  reviewedAt: string | null
  reviewNote: string | null
  createdAt: string
}

export interface CreditLimitRequestDetailResponse {
  id: number
  advertiserAccountId: number
  companyName: string
  currentLimit: number
  requestedLimit: number
  reason: string
  status: CreditLimitRequestStatus
  reviewedAt: string | null
  reviewNote: string | null
  createdAt: string
}

export interface CreateCreditLimitRequest {
  requestedLimit: number
  reason: string
}

export interface RejectCreditLimitRequest {
  reviewNote?: string
}

// === Recommendations ===

export interface RecommendationItem {
  type: string
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  message: string
  campaignId: number | null
  action: string
}

// === Suspend ===

export interface SuspendAdvertiserRequest {
  reason?: string
}

export interface UpdateCreditLimitRequest {
  creditLimit: number
}
