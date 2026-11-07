/**
 * プライバシーポリシーの現行版（版の値の唯一の出どころ）。
 *
 * - 新規登録時の同意記録（`POST /api/v1/auth/register` の `privacyPolicyVersion`）
 * - ポリシー本文の版表示（`landing.legal.privacy.version_notice` の `{version}`）
 *
 * の両方がこの値を参照する。改訂時はここだけを更新すれば、表示と同意記録が食い違わない。
 * BE は版の値を形式（空でない・20 文字以内）だけで受け入れており、既存利用者の再同意は求めない（裁定）。
 */
export const PRIVACY_POLICY_VERSION = '1.1.1'
