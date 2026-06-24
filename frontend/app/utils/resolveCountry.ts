/**
 * locale プレフィックス → ISO 3166-1 alpha-2 国コードのフォールバックマッピング。
 * BE の CountryResolver と同じマッピングを保持する（BE 側が追加した場合はここも更新すること）。
 *
 * 同期先: backend/.../postal/PostalCodePolicyService.java（CountryResolver）
 */
const LOCALE_TO_COUNTRY: Record<string, string> = {
  ja: 'JP',
  en: 'US',
  zh: 'CN',
  ko: 'KR',
  es: 'ES',
  de: 'DE',
}

/**
 * 実効国コードを解決する。
 *
 * 優先順位:
 * 1. countryCode が非空 → 大文字に正規化して返す
 * 2. locale の言語プレフィックスから LOCALE_TO_COUNTRY でマッピング
 * 3. 解決不能 → null
 *
 * @param countryCode - ユーザーが設定した ISO 3166-1 alpha-2 コード（例: "JP", "US"）
 * @param locale      - ユーザーの表示言語（例: "ja", "en-US"）
 */
export function resolveCountry(
  countryCode?: string | null,
  locale?: string | null,
): string | null {
  if (countryCode && countryCode.trim().length > 0) {
    return countryCode.trim().toUpperCase()
  }

  if (locale && locale.trim().length > 0) {
    // "en-US" のような IETF タグは言語サブタグ（最初の "-" より前）を使う
    const lang = locale.trim().split('-')[0].toLowerCase()
    return LOCALE_TO_COUNTRY[lang] ?? null
  }

  return null
}
