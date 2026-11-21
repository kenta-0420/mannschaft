/**
 * お知らせの解除(×)可否。BE deleteAnnouncement の `isAuthor || isAdmin` に合わせる。
 * ピン留めは ADMIN のみ（呼び出し側で isAdmin を直接使う）。
 */
export function canRemoveAnnouncement(
  item: { author: { id: number } | null },
  isAdmin: boolean,
  userId: number | null | undefined,
): boolean {
  if (isAdmin) return true
  return userId != null && item.author?.id === userId
}
