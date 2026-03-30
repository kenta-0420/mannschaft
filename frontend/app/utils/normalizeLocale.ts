/**
 * ブラウザの navigator.language（例: "en-US", "zh-CN"）をアプリの対応ロケールコードに正規化する。
 * BE と FE で同じベースコード（例: "en", "zh"）を使う。
 */
const SUPPORTED_LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number]

export const normalizeLocale = (lang: string): SupportedLocale => {
  const base = lang.split('-')[0].toLowerCase()
  return (SUPPORTED_LOCALES as readonly string[]).includes(base) ? (base as SupportedLocale) : 'ja'
}

export const isSupportedLocale = (code: string): code is SupportedLocale => {
  return (SUPPORTED_LOCALES as readonly string[]).includes(code)
}
