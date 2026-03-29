import type {
  SocialProfile,
  CreateSocialProfileRequest,
  FollowRecord,
  FollowCheckResponse,
  FollowTargetType,
} from '~/types/social-profile'

export function useSocialProfileApi() {
  const api = useApi()

  const PROFILES = '/api/v1/social/profiles'
  const FOLLOWS = '/api/v1/social/follows'

  async function getMyProfile() {
    const res = await api<{ data: SocialProfile }>(`${PROFILES}/me`)
    return res.data
  }

  async function getByHandle(handle: string) {
    const res = await api<{ data: SocialProfile }>(`${PROFILES}/handle/${handle}`)
    return res.data
  }

  async function getByUserId(userId: number) {
    const res = await api<{ data: SocialProfile }>(`${PROFILES}/users/${userId}`)
    return res.data
  }

  async function create(body: CreateSocialProfileRequest) {
    const res = await api<{ data: SocialProfile }>(PROFILES, {
      method: 'POST',
      body,
    })
    return res.data
  }

  async function updateMyProfile(body: Partial<CreateSocialProfileRequest>) {
    const res = await api<{ data: SocialProfile }>(`${PROFILES}/me`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  async function deleteMyProfile() {
    await api(`${PROFILES}/me`, { method: 'DELETE' })
  }

  async function follow(body: { followedType: FollowTargetType; followedId: number }) {
    await api(FOLLOWS, {
      method: 'POST',
      body,
    })
  }

  async function unfollow(body: { followedType: FollowTargetType; followedId: number }) {
    await api(FOLLOWS, { method: 'DELETE', body })
  }

  async function listFollowing(params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: FollowRecord[]; meta: { nextCursor: string | null } }>(
      `${FOLLOWS}/following${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function listFollowers(params?: { cursor?: string; size?: number }) {
    const query = new URLSearchParams()
    if (params?.cursor) query.set('cursor', params.cursor)
    if (params?.size) query.set('size', String(params.size))
    const qs = query.toString()
    const res = await api<{ data: FollowRecord[]; meta: { nextCursor: string | null } }>(
      `${FOLLOWS}/followers${qs ? `?${qs}` : ''}`,
    )
    return res
  }

  async function checkFollow(followedType: FollowTargetType, followedId: number) {
    const res = await api<{ data: FollowCheckResponse }>(
      `${FOLLOWS}/check?followedType=${followedType}&followedId=${followedId}`,
    )
    return res.data
  }

  return {
    getMyProfile,
    getByHandle,
    getByUserId,
    create,
    updateMyProfile,
    deleteMyProfile,
    follow,
    unfollow,
    listFollowing,
    listFollowers,
    checkFollow,
  }
}
