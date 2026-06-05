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

  function base(scopeId: string) {
    return `/api/v1/${scopeType}/${scopeId}/knowledge-base`
  }

  // === Pages ===
  async function getPages(scopeId: string) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/pages`,
    )
  }

  async function getPage(scopeId: string, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}`,
    )
  }

  async function createPage(scopeId: string, body: CreateKbPageRequest) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages`,
      { method: 'POST', body },
    )
  }

  async function updatePage(scopeId: string, pageId: number, body: UpdateKbPageRequest) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}`,
      { method: 'PATCH', body },
    )
  }

  async function deletePage(scopeId: string, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}`, {
      method: 'DELETE',
    })
  }

  async function publishPage(scopeId: string, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/publish`,
      { method: 'POST' },
    )
  }

  async function archivePage(scopeId: string, pageId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/archive`,
      { method: 'POST' },
    )
  }

  async function movePage(scopeId: string, pageId: number, body: MoveKbPageRequest) {
    return api(
      `${base(scopeId)}/pages/${pageId}/move`,
      { method: 'POST', body },
    )
  }

  async function pinPage(scopeId: string, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/pin`, {
      method: 'POST',
    })
  }

  async function unpinPage(scopeId: string, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/pin`, {
      method: 'DELETE',
    })
  }

  async function favoritePage(scopeId: string, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/favorite`, {
      method: 'POST',
    })
  }

  async function unfavoritePage(scopeId: string, pageId: number) {
    return api(`${base(scopeId)}/pages/${pageId}/favorite`, {
      method: 'DELETE',
    })
  }

  // === Search / Discovery ===
  async function searchPages(scopeId: string, params: Record<string, string>) {
    const qs = new URLSearchParams(params).toString()
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/search?${qs}`,
    )
  }

  async function getRecentPages(scopeId: string) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/recent`,
    )
  }

  async function getPinnedPages(scopeId: string) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/pins`,
    )
  }

  async function getFavoritePages(scopeId: string) {
    return api<{ data: KbPageSummaryResponse[] }>(
      `${base(scopeId)}/favorites`,
    )
  }

  // === Revisions ===
  async function getRevisions(scopeId: string, pageId: number) {
    return api<{ data: KbPageRevisionSummaryResponse[] }>(
      `${base(scopeId)}/pages/${pageId}/revisions`,
    )
  }

  async function restoreRevision(scopeId: string, pageId: number, revisionId: number) {
    return api<{ data: KbPageResponse }>(
      `${base(scopeId)}/pages/${pageId}/revisions/${revisionId}/restore`,
      { method: 'POST' },
    )
  }

  // === Upload ===
  async function getUploadUrl(scopeId: string) {
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
