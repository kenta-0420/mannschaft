// === Form Template ===
export interface FormTemplateResponse {
  id: number
  scopeType: string
  scopeId: number
  name: string
  description: string | null
  icon: string | null
  color: string | null
  status: string
  requiresApproval: boolean
  workflowTemplateId: number | null
  isSealOnPdf: boolean
  deadline: string | null
  allowEditAfterSubmit: boolean
  autoFillEnabled: boolean
  maxSubmissionsPerUser: number | null
  sortOrder: number
  presetId: number | null
  submissionCount: number
  targetCount: number | null
  createdBy: number
  publishedAt: string | null
  closedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  fields: FormFieldResponse[]
}

export interface FormFieldResponse {
  id: number
  templateId: number
  fieldKey: string
  fieldLabel: string | null
  fieldType: string | null
  isRequired: boolean
  sortOrder: number
  autoFillKey: string | null
  optionsJson: string | null
  placeholder: string | null
}

export interface FormFieldRequest {
  fieldKey?: string
  fieldLabel?: string
  fieldType?: string
  isRequired?: boolean
  sortOrder?: number
  autoFillKey?: string
  optionsJson?: string
  placeholder?: string
}

export interface CreateFormTemplateRequest {
  name?: string
  description?: string
  icon?: string
  color?: string
  requiresApproval?: boolean
  workflowTemplateId?: number
  isSealOnPdf?: boolean
  deadline?: string
  allowEditAfterSubmit?: boolean
  autoFillEnabled?: boolean
  maxSubmissionsPerUser?: number
  sortOrder?: number
  presetId?: number
  targetCount?: number
  fields?: FormFieldRequest[]
}

export interface UpdateFormTemplateRequest {
  name?: string
  description?: string
  icon?: string
  color?: string
  requiresApproval?: boolean
  workflowTemplateId?: number
  isSealOnPdf?: boolean
  deadline?: string
  allowEditAfterSubmit?: boolean
  autoFillEnabled?: boolean
  maxSubmissionsPerUser?: number
  sortOrder?: number
  targetCount?: number
  fields?: FormFieldRequest[]
}

// === Form Submission ===
export interface FormSubmissionResponse {
  id: number
  templateId: number
  scopeType: string
  scopeId: number
  status: string
  submittedBy: number
  workflowRequestId: number | null
  pdfFileKey: string | null
  submissionCountForUser: number
  version: number
  createdAt: string
  updatedAt: string
  values: SubmissionValueResponse[]
}

export interface SubmissionValueResponse {
  id: number
  submissionId: number
  fieldKey: string
  fieldType: string | null
  textValue: string | null
  numberValue: number | null
  dateValue: string | null
  fileKey: string | null
  isAutoFilled: boolean
  createdAt: string
}

export interface SubmissionValueRequest {
  fieldKey?: string
  fieldType?: string
  textValue?: string
  numberValue?: number
  dateValue?: string
  fileKey?: string
  isAutoFilled?: boolean
}

export interface CreateFormSubmissionRequest {
  templateId: number
  submitImmediately?: boolean
  values?: SubmissionValueRequest[]
}

export interface UpdateFormSubmissionRequest {
  submitImmediately?: boolean
  values?: SubmissionValueRequest[]
}
