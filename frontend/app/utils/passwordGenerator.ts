/** 本人用の固定16文字パスワードをブラウザ内で生成する。 */
const UPPERCASE = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
const LOWERCASE = 'abcdefghijklmnopqrstuvwxyz'
const DIGITS = '0123456789'
const ALPHANUMERIC = UPPERCASE + LOWERCASE + DIGITS

/** 剰余の偏りを避け、文字選択とシャッフルで同じ乱数源を使う。 */
function randomIndex(size: number): number {
  const limit = 256 - (256 % size)
  const bytes = new Uint8Array(1)
  do {
    globalThis.crypto.getRandomValues(bytes)
  } while (bytes[0]! >= limit)
  return bytes[0]! % size
}

export function generatePassword(): string {
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error('Secure random generation is unavailable')
  }
  const characters = [
    UPPERCASE[randomIndex(26)]!,
    LOWERCASE[randomIndex(26)]!,
    DIGITS[randomIndex(10)]!,
  ]
  while (characters.length < 16) characters.push(ALPHANUMERIC[randomIndex(ALPHANUMERIC.length)]!)
  for (let i = characters.length - 1; i > 0; i--) {
    const index = randomIndex(i + 1)
    ;[characters[i], characters[index]] = [characters[index]!, characters[i]!]
  }
  return characters.join('')
}
