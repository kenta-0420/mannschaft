import type { FollowRecord, FollowListVisibility } from '~/types/social-profile'

/**
 * フォロー一覧・公開設定管理 composable
 * カーソルベースのページネーションに対応
 */
export function useFollowList() {
  const socialApi = useSocialProfileApi()

  const following = ref<FollowRecord[]>([])
  const followers = ref<FollowRecord[]>([])
  const followingCursor = ref<string | null>(null)
  const followersCursor = ref<string | null>(null)
  const followingLoading = ref(false)
  const followersLoading = ref(false)

  // 自分のフォロー中一覧（初回 or リフレッシュ）
  async function loadMyFollowing(size = 20) {
    followingLoading.value = true
    try {
      const res = await socialApi.listFollowing({ size })
      following.value = res.data
      followingCursor.value = res.meta.nextCursor
    } finally {
      followingLoading.value = false
    }
  }

  // 自分のフォロー中一覧（次ページ追加読み込み）
  async function loadMoreMyFollowing(size = 20) {
    if (!followingCursor.value || followingLoading.value) return
    followingLoading.value = true
    try {
      const res = await socialApi.listFollowing({ cursor: followingCursor.value, size })
      following.value.push(...res.data)
      followingCursor.value = res.meta.nextCursor
    } finally {
      followingLoading.value = false
    }
  }

  // 自分のフォロワー一覧（初回 or リフレッシュ）
  async function loadMyFollowers(size = 20) {
    followersLoading.value = true
    try {
      const res = await socialApi.listFollowers({ size })
      followers.value = res.data
      followersCursor.value = res.meta.nextCursor
    } finally {
      followersLoading.value = false
    }
  }

  // 自分のフォロワー一覧（次ページ追加読み込み）
  async function loadMoreMyFollowers(size = 20) {
    if (!followersCursor.value || followersLoading.value) return
    followersLoading.value = true
    try {
      const res = await socialApi.listFollowers({ cursor: followersCursor.value, size })
      followers.value.push(...res.data)
      followersCursor.value = res.meta.nextCursor
    } finally {
      followersLoading.value = false
    }
  }

  // 他ユーザーのフォロー中一覧
  async function getUserFollowing(userId: number, params?: { cursor?: string; size?: number }) {
    return await socialApi.getUserFollowing(userId, params)
  }

  // 他ユーザーのフォロワー一覧
  async function getUserFollowers(userId: number, params?: { cursor?: string; size?: number }) {
    return await socialApi.getUserFollowers(userId, params)
  }

  // フォロー一覧公開設定 取得
  async function getFollowListVisibility(): Promise<FollowListVisibility> {
    return await socialApi.getFollowListVisibility()
  }

  // フォロー一覧公開設定 更新
  async function updateFollowListVisibility(visibility: FollowListVisibility): Promise<void> {
    await socialApi.updateFollowListVisibility(visibility)
  }

  return {
    following,
    followers,
    followingCursor,
    followersCursor,
    followingLoading,
    followersLoading,
    loadMyFollowing,
    loadMoreMyFollowing,
    loadMyFollowers,
    loadMoreMyFollowers,
    getUserFollowing,
    getUserFollowers,
    getFollowListVisibility,
    updateFollowListVisibility,
  }
}
