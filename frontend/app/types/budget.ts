export type FiscalYearStatus = 'DRAFT' | 'OPEN' | 'CLOSED'
export type BudgetCategoryType = 'INCOME' | 'EXPENSE'
export type TransactionApprovalStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'

export interface FiscalYearResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  startDate: string
  endDate: string
  status: FiscalYearStatus
  totalBudget: number
  totalSpent: number
  budgetBurnPercent: number
  createdAt: string
}

export interface BudgetCategoryResponse {
  id: number
  fiscalYearId: number
  parentId: number | null
  name: string
  categoryType: BudgetCategoryType
  allocatedAmount: number
  spentAmount: number
  burnPercent: number
  sortOrder: number
  children: BudgetCategoryResponse[]
}

export interface BudgetTransactionResponse {
  id: number
  fiscalYearId: number
  categoryId: number
  categoryName: string
  amount: number
  description: string
  transactionDate: string
  isAutoRecorded: boolean
  sourceType: string | null
  sourceId: number | null
  reversalOfId: number | null
  approvalStatus: TransactionApprovalStatus
  approvedBy: { id: number; displayName: string } | null
  recordedBy: { id: number; displayName: string } | null
  attachments: Array<{ id: number; fileName: string; url: string }>
  createdAt: string
}

export interface BudgetSummary {
  fiscalYearId: number
  totalIncome: number
  totalExpense: number
  balance: number
  byCategory: Array<{
    categoryId: number
    categoryName: string
    categoryType: BudgetCategoryType
    allocated: number
    actual: number
    burnPercent: number
  }>
}

export interface BudgetConfig {
  warningThreshold: number
  criticalThreshold: number
  requireApprovalAbove: number | null
}
