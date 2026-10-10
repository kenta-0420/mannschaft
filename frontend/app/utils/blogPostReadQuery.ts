export interface BlogPostReadQuery {
 teamId?: string
 organizationId?: string
 userId?: string
 previewToken?: string
}

/** 既slug閲覧のquery境界。IDはLong正準文字列のまま送り、権限はBEが再判定する。 */
export function parseBlogPostReadQuery(query: Readonly<Record<string, unknown>>): BlogPostReadQuery {
 const result: BlogPostReadQuery = {}
 let selected = 0
 for (const field of ['teamId', 'organizationId', 'userId'] as const) {
  const value = query[field]
  if (value === undefined) continue
  selected += 1
  if (selected > 1 || typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)
      || BigInt(value) > 9223372036854775807n) throw new Error('BLOG_READ_QUERY_INVALID')
  result[field] = value
 }
 const previewToken = query.previewToken
 if (previewToken !== undefined) {
  if (typeof previewToken !== 'string' || previewToken.length === 0) throw new Error('BLOG_READ_QUERY_INVALID')
  result.previewToken = previewToken
 }
 // tracking等の未知queryをAPIのactor/scope指定へ転送しない。
 return result
}
