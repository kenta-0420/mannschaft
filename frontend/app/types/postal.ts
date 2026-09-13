// 国別郵便番号バリデーションポリシー。
// GET /api/v1/postal-code/policies が返す各エントリに対応する手動型。
// OpenAPI生成型はプロパティをoptionalとして出力するため、UIで必須とする契約をこの型で明示する。
export interface PostalCodePolicy {
  /** ISO 3166-1 alpha-2 国コード（例: "JP", "US"） */
  countryCode: string
  /** 検証用正規表現文字列（例: "^\\d{3}-?\\d{4}$"） */
  pattern: string
  /** 入力例（例: "123-4567"） */
  example: string
}
