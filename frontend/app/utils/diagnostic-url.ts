const INVALID_DIAGNOSTIC_URL = '/invalid-url'

/** 診断専用。リンク自体は変更せず、path中のencoded delimiterもdecodeしない。 */
export function safeDiagnosticUrl(input: string | null | undefined): string {
  if (
    !input ||
    Array.from(input).some((character) => {
      const code = character.charCodeAt(0)
      return code <= 32 || (code >= 127 && code <= 159)
    }) ||
    /[\s\\<>"{}|^`]/u.test(input) ||
    /%(?![0-9a-f]{2})/i.test(input)
  ) {
    return INVALID_DIAGNOSTIC_URL
  }
  const withoutQuery = input.split(/[?#]/, 1)[0]!
  if (withoutQuery.startsWith('/') && !withoutQuery.startsWith('//')) {
    return withoutQuery.includes('[') || withoutQuery.includes(']')
      ? INVALID_DIAGNOSTIC_URL
      : withoutQuery
  }
  const absolute = /^(https?):\/\/([^/?#]+)(\/[^?#]*)?$/i.exec(withoutQuery)
  if (!absolute) return INVALID_DIAGNOSTIC_URL
  try {
    const parsed = new URL(input)
    const authority = absolute[2]!
    const hostAndPort = authority.slice(authority.lastIndexOf('@') + 1)
    // WHATWGの補正によりhostlessや複数@を別の有効URLへ救済しない。
    if (!parsed.hostname || authority.split('@').length > 2 || /[^\x21-\x7e]/.test(authority)) {
      return INVALID_DIAGNOSTIC_URL
    }
    if (!hostAndPort.startsWith('[')) {
      const host = hostAndPort.split(':', 1)[0]!
      if (
        parsed.hostname.toLowerCase() !== host.toLowerCase() ||
        !host
          .replace(/\.$/, '')
          .split('.')
          .every((label) => /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label))
      ) {
        return INVALID_DIAGNOSTIC_URL
      }
    }
    const path = absolute[3] || '/'
    if (path.includes('[') || path.includes(']')) return INVALID_DIAGNOSTIC_URL
    return `${absolute[1]!.toLowerCase()}://${hostAndPort}${path}`
  } catch {
    return INVALID_DIAGNOSTIC_URL
  }
}
