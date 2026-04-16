/**
 * F01.5 フレンドフォルダ 型定義。
 *
 * バックエンド DTO（{@code com.mannschaft.app.social.dto.TeamFriendFolderView} 等）
 * に対応する TypeScript 型。JSON プロパティ名は camelCase 記法で統一する
 * （バックエンド側 {@code @JsonProperty(camelCase)} および Jackson デフォルト設定と整合）。
 *
 * Phase 1 では {@code autoForwardEnabled} / {@code autoForwardTarget} は
 * 存在しない（Phase 3 で追加予定）。
 */

/** フレンドフォルダ View */
export interface TeamFriendFolderView {
  /** フォルダ ID */
  id: number
  /** フォルダ名（1〜50 文字、チーム内一意） */
  name: string
  /** フォルダの説明（任意、最大 300 文字） */
  description?: string | null
  /** 表示色（HEX、例: "#10B981"） */
  color: string
  /** 並び替え順（任意） */
  sortOrder?: number | null
  /** デフォルトフォルダかどうか（任意） */
  isDefault?: boolean | null
  /** フォルダに登録されているフレンド数 */
  memberCount: number
  /** フォルダ作成日時（ISO 8601） */
  createdAt: string
  /** フォルダ最終更新日時（ISO 8601） */
  updatedAt: string
}

/** フォルダ作成リクエスト */
export interface CreateFolderRequest {
  /** フォルダ名（1〜50 文字、チーム内一意） */
  name: string
  /** フォルダの説明（任意、最大 300 文字） */
  description?: string
  /** 表示色（HEX） */
  color: string
}

/** フォルダ更新リクエスト（全量更新） */
export interface UpdateFolderRequest {
  /** フォルダ名 */
  name?: string
  /** フォルダの説明 */
  description?: string
  /** 表示色（HEX） */
  color?: string
  /** 並び替え順（0 以上の整数） */
  sortOrder?: number
}

/** フォルダメンバー追加リクエスト（Phase 1 は単一 ID 追加） */
export interface AddFolderMemberRequest {
  /** 追加対象の team_friends.id */
  teamFriendId: number
}
