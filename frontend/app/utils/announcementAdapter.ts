import type { AnnouncementFeedDto, AnnouncementFeedItem } from '~/types/announcement'

/** generated DTO から表示名だけを変換する。古い実在 sourceUrl は保持し、URL を捏造しない。 */
type FeedInput = Partial<AnnouncementFeedDto> & Partial<Pick<AnnouncementFeedItem,
  'title' | 'excerpt' | 'sourceUrl' | 'author' | 'sourceMeta' | 'pinnedAt' | 'contentPreviewAvailable'
  | 'isAdvertisement' | 'advertiserAccountId' | 'messagingCampaignId' | 'channelType'>>

export function toAnnouncementItem(input: FeedInput): AnnouncementFeedItem {
  if (!input.id || !input.scopeId || !['TEAM', 'ORGANIZATION'].includes(input.scopeType ?? '')) {
    throw new Error('Announcement feed has no valid owning scope')
  }
  const locked = input.accessState === 'LOCKED'
  const sourceType = locked ? null : input.sourceType ?? null
  return {
    id: input.id,
    scopeType: input.scopeType as AnnouncementFeedItem['scopeType'],
    scopeId: input.scopeId,
    sourceType,
    sourceId: locked ? null : input.sourceId ?? null,
    sourceUrl: locked ? null : input.sourceUrl ?? null,
    title: input.titleCache ?? input.title ?? '',
    excerpt: locked ? null : input.excerptCache ?? input.excerpt ?? null,
    priority: (input.priority ?? 'NORMAL') as AnnouncementFeedItem['priority'],
    isPinned: input.isPinned === true,
    isRead: input.isRead === true,
    pinnedAt: input.pinnedAt ?? null,
    visibility: (input.visibility ?? 'PUBLIC') as AnnouncementFeedItem['visibility'],
    author: input.author ?? null,
    sourceMeta: locked ? null : input.sourceMeta ?? null,
    startsAt: input.startsAt ?? null,
    expiresAt: input.expiresAt ?? null,
    createdAt: input.createdAt ?? '',
    accessState: locked ? 'LOCKED' : 'FULL',
    contentPreviewAvailable: input.contentPreviewAvailable
      ?? (!locked && (sourceType === 'BLOG_POST' || sourceType === 'BULLETIN_THREAD')),
    isAdvertisement: input.isAdvertisement,
    advertiserAccountId: input.advertiserAccountId,
    messagingCampaignId: input.messagingCampaignId,
    channelType: input.channelType,
  }
}

/** Dashboard の既存 snake_case 集約を同じ adapter へ渡す。各feed所有scopeは必須。 */
export function toDashboardAnnouncementItem(input: Record<string, unknown>): AnnouncementFeedItem {
  const number = (key: string): number | undefined => typeof input[key] === 'number' ? input[key] as number : undefined
  const string = (key: string): string | undefined => typeof input[key] === 'string' ? input[key] as string : undefined
  return toAnnouncementItem({
    id: number('id'), scopeId: number('scope_id'), scopeType: string('scope_type'),
    sourceId: number('source_id'), sourceType: string('source_type'),
    titleCache: string('title_cache'), excerptCache: string('excerpt_cache'),
    sourceUrl: string('source_url'),
    priority: string('priority'), accessState: string('access_state'),
    isPinned: input.is_pinned === true, isRead: input.is_read === true,
    contentPreviewAvailable: typeof input.content_preview_available === 'boolean' ? input.content_preview_available : undefined,
    createdAt: string('created_at'), expiresAt: string('expires_at'), startsAt: string('starts_at'),
  })
}
