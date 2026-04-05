import type {
  ApprovalDecisionRequest,
  CreateWorkflowRequestRequest,
  CreateWorkflowTemplateRequest,
  PageMeta,
  UpdateWorkflowRequestRequest,
  UpdateWorkflowTemplateRequest,
  WorkflowAttachmentResponse,
  WorkflowCommentRequest,
  WorkflowCommentResponse,
  WorkflowRequestResponse,
  WorkflowTemplateResponse,
} from '~/types/workflow'

interface WorkflowListParams {
  status?: string
  page?: number
  size?: number
}

export function useWorkflowApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Workflow Templates ===
  async function listTemplates(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: WorkflowListParams,
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: WorkflowTemplateResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/workflow-templates?${query}`,
    )
  }

  async function getTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api<{ data: WorkflowTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-templates/${templateId}`,
    )
  }

  async function createTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateWorkflowTemplateRequest,
  ) {
    return api<{ data: WorkflowTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-templates`,
      { method: 'POST', body },
    )
  }

  async function updateTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    body: UpdateWorkflowTemplateRequest,
  ) {
    return api<{ data: WorkflowTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-templates/${templateId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/workflow-templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  async function activateTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/workflow-templates/${templateId}/activate`, {
      method: 'POST',
    })
  }

  async function deactivateTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/workflow-templates/${templateId}/deactivate`, {
      method: 'POST',
    })
  }

  // === Workflow Requests ===
  async function listRequests(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: WorkflowListParams,
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: WorkflowRequestResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests?${query}`,
    )
  }

  async function getRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    requestId: number,
  ) {
    return api<{ data: WorkflowRequestResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests/${requestId}`,
    )
  }

  async function createRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateWorkflowRequestRequest,
  ) {
    return api<{ data: WorkflowRequestResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests`,
      { method: 'POST', body },
    )
  }

  async function updateRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    requestId: number,
    body: UpdateWorkflowRequestRequest,
  ) {
    return api<{ data: WorkflowRequestResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests/${requestId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    requestId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/workflow-requests/${requestId}`, {
      method: 'DELETE',
    })
  }

  async function submitRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    requestId: number,
  ) {
    return api<{ data: WorkflowRequestResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests/${requestId}/submit`,
      { method: 'POST' },
    )
  }

  async function withdrawRequest(
    scopeType: 'team' | 'organization',
    scopeId: number,
    requestId: number,
  ) {
    return api<{ data: WorkflowRequestResponse }>(
      `${buildBase(scopeType, scopeId)}/workflow-requests/${requestId}/withdraw`,
      { method: 'POST' },
    )
  }

  // === Decision ===
  async function decideRequest(requestId: number, body: ApprovalDecisionRequest) {
    return api(`/api/v1/workflow-requests/${requestId}/decide`, { method: 'POST', body })
  }

  // === Comments ===
  async function listComments(requestId: number) {
    return api<{ data: WorkflowCommentResponse[] }>(
      `/api/v1/workflow-requests/${requestId}/comments`,
    )
  }

  async function addComment(requestId: number, body: WorkflowCommentRequest) {
    return api<{ data: WorkflowCommentResponse }>(
      `/api/v1/workflow-requests/${requestId}/comments`,
      { method: 'POST', body },
    )
  }

  async function updateComment(requestId: number, commentId: number, body: WorkflowCommentRequest) {
    return api<{ data: WorkflowCommentResponse }>(
      `/api/v1/workflow-requests/${requestId}/comments/${commentId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteComment(requestId: number, commentId: number) {
    return api(`/api/v1/workflow-requests/${requestId}/comments/${commentId}`, { method: 'DELETE' })
  }

  // === Attachments ===
  async function listAttachments(requestId: number) {
    return api<{ data: WorkflowAttachmentResponse[] }>(
      `/api/v1/workflow-requests/${requestId}/attachments`,
    )
  }

  async function uploadAttachment(requestId: number, file: File) {
    const formData = new FormData()
    formData.append('file', file)
    return api<{ data: WorkflowAttachmentResponse }>(
      `/api/v1/workflow-requests/${requestId}/attachments`,
      { method: 'POST', body: formData },
    )
  }

  return {
    listTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    activateTemplate,
    deactivateTemplate,
    listRequests,
    getRequest,
    createRequest,
    updateRequest,
    deleteRequest,
    submitRequest,
    withdrawRequest,
    decideRequest,
    listComments,
    addComment,
    updateComment,
    deleteComment,
    listAttachments,
    uploadAttachment,
  }
}
