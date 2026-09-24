import type { BlogPostMeta } from '~/types/cms'

export function resolveBlogPublicVisible(
  meta: Pick<BlogPostMeta, 'publicVisible'> | null | undefined,
): boolean {
  return meta?.publicVisible ?? true
}
