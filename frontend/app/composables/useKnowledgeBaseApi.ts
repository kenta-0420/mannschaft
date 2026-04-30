import type {
  KbPageResponse,
  KbPageSummaryResponse,
  KbPageRevisionSummaryResponse,
  CreateKbPageRequest,
  UpdateKbPageRequest,
  MoveKbPageRequest,
} from '~/types/knowledgeBase'

export type KbScopeType = 'teams' | 'organizations'

export function useKnowledgeBaseApi(scopeType: KbScopeType = 'teams') {
  const api = useApi()

  function base(scopeId: number) {
    return `/api/v1/${scopeType}/${scopeId}/knowledge-base`
  }

  // === Pages ===
  async function getPages(scopeId: number) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/pages`,
    )
  }

  async function getPage(scopeId: number, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}`,
    )
  }

  async function createPage(scopeId: number, body: CreateKbPageRequest) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages`,
      { method: 'POST', body },
    )
  }

  async function updatePage(scopeId: number, pageId: number, body: UpdateKbPageRequest) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}`,
      { method: 'PATCH', body },
    )
  }

  async function deletePage(scopeId: number, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}`, {
      method: 'DELETE',
    })
  }

  async function publishPage(scopeId: number, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/publish`,
      { method: 'POST' },
    )
  }

  async function archivePage(scopeId: number, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/archive`,
      { method: 'POST' },
    )
  }

  async function movePage(scopeId: number, pageId: number, body: MoveKbPageRequest) {
    return api(
      `${base(scopeId)}/pages/${pageId}/move`,
      { method: 'POST', body },
    )
  }

  async function pinPage(scopeId: number, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/pin`, {
      method: 'POST',
    })
  }

  async function unpinPage(scopeId: number, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/pin`, {
      method: 'DELETE',
    })
  }

  async function favoritePage(scopeId: number, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/favorite`, {
      method: 'POST',
    })
  }

  async function unfavoritePage(scopeId: number, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/favorite`, {
      method: 'DELETE',
    })
  }

  // === Search / Discovery ===
  async function searchPages(scopeId: number, params: Record<string, string>) {
    const qs = new URLSearchParams(params).toString()
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/search?${qs}`,
    )
  }

  async function getRecentPages(scopeId: number) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/recent`,
    )
  }

  async function getPinnedPages(scopeId: number) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/pins`,
    )
  }

  async function getFavoritePages(scopeId: number) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/favorites`,
    )
  }

  // === Revisions ===
  async function getRevisions(scopeId: number, pageId: number) {
    return api<{ data: KbPageRevisionSummaryResponse[] }>(
      `${base(scopeId)}/pages/${pageId}/revisions`,
    )
  }

  async function restoreRevision(scopeId: number, pageId: number, revisionId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/revisions/${revisionId}/restore`,
      { method: 'POST' },
    )
  }

  // === Upload ===
  async function getUploadUrl(scopeId: number) {
    return api<{ data: { uploadUrl: string; s3Key: string } }>(
      `${base(scopeId)}/upload-url`,
      { method: 'POST' },
    )
  }

  return {
    getPages,
    getPage,
    createPage,
    updatePage,
    deletePage,
    publishPage,
    archivePage,
    movePage,
    pinPage,
    unpinPage,
    favoritePage,
    unfavoritePage,
    searchPages,
    getRecentPages,
    getPinnedPages,
    getFavoritePages,
    getRevisions,
    restoreRevision,
    getUploadUrl,
  }
}
