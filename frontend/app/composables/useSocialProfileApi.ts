import type { SocialProfile, CreateSocialProfileRequest, FollowRecord, FollowCheckResponse, FollowTargetType } from '~/types/social-profile'

export function useSocialProfileApi() {
  const api = useApi()

  async function listMy() {
    const res = await api<{ data: SocialProfile[] }>('/api/v1/social-profiles/me')
    return res.data
  }

  async function getByHandle(handle: string) {
    const res = await api<{ data: SocialProfile }>(`/api/v1/social-profiles/handle/${handle}`)
    return res.data
  }

  async function create(body: CreateSocialProfileRequest) {
    const res = await api<{ data: SocialProfile }>('/api/v1/social-profiles', {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function update(id: number, body: Partial<CreateSocialProfileRequest>) {
    const res = await api<{ data: SocialProfile }>(`/api/v1/social-profiles/${id}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  async function remove(id: number) {
    await api(`/api/v1/social-profiles/${id}`, { method: 'DELETE' })
  }

  async function follow(followedType: FollowTargetType, followedId: number) {
    await api('/api/v1/follows', {
      method: 'POST',
      body: { followedType, followedId },
    })
  }

  async function unfollow(followedType: FollowTargetType, followedId: number) {
    await api(`/api/v1/follows/${followedType}/${followedId}`, { method: 'DELETE' })
  }

  async function listFollowing(params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: FollowRecord[]; meta: { nextCursor: string | null } }>(
      `/api/v1/follows/following${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function listFollowers(params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: FollowRecord[]; meta: { nextCursor: string | null } }>(
      `/api/v1/follows/followers${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function checkFollow(followedType: FollowTargetType, followedId: number) {
    const res = await api<{ data: FollowCheckResponse }>(
      `/api/v1/follows/check?followedType=${followedType}&followedId=${followedId}`,
    )
    return res.data
  }

  return { listMy, getByHandle, create, update, remove, follow, unfollow, listFollowing, listFollowers, checkFollow }
}
