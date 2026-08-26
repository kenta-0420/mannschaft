// 国別郵便番号バリデーションポリシー。
// GET /api/v1/postal-code/policies が返す各エントリに対応する手動型。
// TODO: openapi.json 再生成後に生成型（types/generated/index.ts）へ移行予定。
// （BE PostalCodePoliciesController が openapi.json 未掲載のため、暫定手動型として管理）
export interface PostalCodePolicy {
  /** ISO 3166-1 alpha-2 国コード（例: "JP", "US"） */
  countryCode: string
  /** 検証用正規表現文字列（例: "^\\d{3}-?\\d{4}$"） */
  pattern: string
  /** 入力例（例: "123-4567"） */
  example: string
}
