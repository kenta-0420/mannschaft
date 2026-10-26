import type {
  SnsFeedConfigResponse,
  CreateSnsFeedConfigRequest,
  UpdateSnsFeedConfigRequest,
  SnsFeedPreviewResponse,
} from '~/types/sns-feed'

export function useSnsFeedApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: string) {
    return scopeType === 'team'
      ? `/api/v1/teams/${scopeId}/sns/feeds`
      : `/api/v1/organizations/${scopeId}/sns/feeds`
  }

  async function listFeeds(scopeType: 'team' | 'organization', scopeId: string) {
    return api<{ data: SnsFeedConfigResponse[] }>(buildBase(scopeType, scopeId))
  }

  async function createFeed(
    scopeType: 'team' | 'organization',
    scopeId: string,
    body: CreateSnsFeedConfigRequest,
  ) {
    return api<{ data: SnsFeedConfigResponse }>(buildBase(scopeType, scopeId), {
      method: 'POST',
      body,
    })
  }

  async function updateFeed(
    scopeType: 'team' | 'organization',
    scopeId: string,
    feedId: number,
    body: UpdateSnsFeedConfigRequest,
  ) {
    return api<{ data: SnsFeedConfigResponse }>(`${buildBase(scopeType, scopeId)}/${feedId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteFeed(scopeType: 'team' | 'organization', scopeId: string, feedId: number) {
    return api(`${buildBase(scopeType, scopeId)}/${feedId}`, { method: 'DELETE' })
  }

  async function previewFeed(scopeType: 'team' | 'organization', scopeId: string, feedId: number) {
    return api<{ data: SnsFeedPreviewResponse }>(
      `${buildBase(scopeType, scopeId)}/${feedId}/preview`,
    )
  }

  return {
    listFeeds,
    createFeed,
    updateFeed,
    deleteFeed,
    previewFeed,
  }
}
