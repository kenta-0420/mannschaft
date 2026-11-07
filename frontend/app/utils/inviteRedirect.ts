/** 招待からの登録・ログインで保持できる内部復帰先だけを受け入れる。 */
export function inviteRedirect(value: unknown): string | undefined {
  if (
    typeof value !== 'string' ||
    value.includes('\\') ||
    [...value].some((character) => character.charCodeAt(0) <= 32)
  )
    return undefined
  return /^\/invite\/[A-Za-z0-9_-]+(?:[?#].*)?$/.test(value) ? value : undefined
}
