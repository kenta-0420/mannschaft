// === Form Template ===
export interface FormTemplateScopeDto {
  scopeType: string
  scopeId: string
}
export interface FormTemplateContentDto {
  name: string
  description: string | null
  icon: string | null
  color: string | null
  sortOrder: number
}
export interface FormTemplateWorkflowDto {
  requiresApproval: boolean
  workflowTemplateId: number | null
  isSealOnPdf: boolean
}
export interface FormTemplateEditPolicyDto {
  allowEditAfterSubmit: boolean
  autoFillEnabled: boolean
  maxSubmissionsPerUser: number | null
}
export interface FormTemplateStatsDto {
  submissionCount: number
  targetCount: number | null
  presetId: number | null
}
export interface FormTemplateTimelineDto {
  deadline: string | null
  publishedAt: string | null
  closedAt: string | null
}
export interface FormTemplateAuditDto {
  version: number
  createdBy: number
  createdAt: string
  updatedAt: string
}

export interface FormTemplateResponse {
  id: number
  status: string
  scope: FormTemplateScopeDto
  content: FormTemplateContentDto
  workflow: FormTemplateWorkflowDto
  editPolicy: FormTemplateEditPolicyDto
  stats: FormTemplateStatsDto
  timeline: FormTemplateTimelineDto
  audit: FormTemplateAuditDto
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
export interface FormSubmissionScopeDto {
  scopeType: string
  scopeId: string
}
export interface FormSubmissionMetaDto {
  templateId: number
  submittedBy: number
  workflowRequestId: number | null
  submissionCountForUser: number
  version: number
}
export interface FormSubmissionPdfDto {
  pdfFileKey: string | null
}
export interface FormSubmissionAuditDto {
  createdAt: string
  updatedAt: string
}

export interface FormSubmissionResponse {
  id: number
  status: string
  scope: FormSubmissionScopeDto
  meta: FormSubmissionMetaDto
  pdf: FormSubmissionPdfDto
  audit: FormSubmissionAuditDto
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
