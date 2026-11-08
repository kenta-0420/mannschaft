/** 認証器のバイナリ応答をAPIのbase64url形式へ変換する。 */
export function bufToB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
}

/** APIのbase64url形式を認証器が受け取るバイナリへ変換する。 */
export function b64urlToBuf(b64url: string): ArrayBuffer {
  const base64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}
