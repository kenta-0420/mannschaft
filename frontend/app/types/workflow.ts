// === Workflow Template ===
export interface WorkflowTemplateResponse {
  id: number
  scopeType: string
  scopeId: number
  name: string
  description: string | null
  icon: string | null
  color: string | null
  isSealRequired: boolean
  isActive: boolean
  sortOrder: number
  createdBy: number
  version: number
  createdAt: string
  updatedAt: string
  steps: TemplateStepResponse[]
  fields: TemplateFieldResponse[]
}

export interface TemplateStepResponse {
  id: number
  templateId: number
  stepOrder: number
  name: string | null
  approvalType: string | null
  approverType: string | null
  approverUserIds: string | null
  approverRole: string | null
  autoApproveDays: number | null
}

export interface TemplateFieldResponse {
  id: number
  templateId: number
  fieldKey: string
  fieldLabel: string | null
  fieldType: string | null
  isRequired: boolean
  sortOrder: number
  optionsJson: string | null
}

export interface TemplateStepRequest {
  stepOrder: number
  name?: string
  approvalType?: string
  approverType?: string
  approverUserIds?: string
  approverRole?: string
  autoApproveDays?: number
}

export interface TemplateFieldRequest {
  fieldKey?: string
  fieldLabel?: string
  fieldType?: string
  isRequired: boolean
  sortOrder?: number
  optionsJson?: string
}

export interface CreateWorkflowTemplateRequest {
  name?: string
  description?: string
  icon?: string
  color?: string
  isSealRequired: boolean
  sortOrder?: number
  steps?: TemplateStepRequest[]
  fields?: TemplateFieldRequest[]
}

export interface UpdateWorkflowTemplateRequest {
  name?: string
  description?: string
  icon?: string
  color?: string
  isSealRequired: boolean
  sortOrder?: number
  version: number
  steps?: TemplateStepRequest[]
  fields?: TemplateFieldRequest[]
}

// === Workflow Request ===
export interface WorkflowRequestResponse {
  id: number
  templateId: number
  scopeType: string
  scopeId: number
  title: string | null
  status: string
  requestedBy: number
  requestedAt: string | null
  currentStepOrder: number | null
  fieldValues: string | null
  version: number
  sourceType: string | null
  sourceId: number | null
  createdAt: string
  updatedAt: string
  steps: RequestStepResponse[]
}

export interface RequestStepResponse {
  id: number
  requestId: number
  stepOrder: number
  status: string
  completedAt: string | null
  createdAt: string
  approvers: ApproverResponse[]
}

export interface ApproverResponse {
  id: number
  requestStepId: number
  approverUserId: number
  decision: string | null
  decisionAt: string | null
  decisionComment: string | null
  sealId: number | null
  createdAt: string
}

export interface CreateWorkflowRequestRequest {
  templateId: number
  title?: string
  fieldValues?: string
  sourceType?: string
  sourceId?: number
}

export interface UpdateWorkflowRequestRequest {
  title?: string
  fieldValues?: string
  version: number
}

export interface ApprovalDecisionRequest {
  decision?: string
  comment?: string
  sealId?: number
}

// === Workflow Comment ===
export interface WorkflowCommentResponse {
  id: number
  requestId: number
  userId: number
  body: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowCommentRequest {
  body?: string
}

// === Workflow Attachment ===
export interface WorkflowAttachmentResponse {
  id: number
  requestId: number
  fileKey: string
  originalFilename: string | null
  fileSize: number | null
  uploadedBy: number
  createdAt: string
}

// === Paged ===
export interface PageMeta {
  page: number
  size: number
  totalElements: number
  totalPages: number
}
