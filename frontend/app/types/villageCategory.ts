/**
 * 村カテゴリ型定義
 *
 * Backend: VillageCategoryResponse / VillageCategoryRequest
 * API:
 *   GET  /api/v1/village-categories                    ← 一般ユーザー向け（ツリー構造）
 *   GET  /api/v1/system-admin/village-categories       ← SYSTEM_ADMIN 向け
 *   POST /api/v1/system-admin/village-categories       ← 作成
 *   PUT  /api/v1/system-admin/village-categories/{id}  ← 更新
 *   DELETE /api/v1/system-admin/village-categories/{id} ← 論理削除
 */

/** 村カテゴリのレスポンス型（ツリー構造） */
export interface VillageCategoryResponse {
  id: string
  name: string
  parentId: string | null
  displayOrder: number
  children: VillageCategoryResponse[]
}

/** 村カテゴリの作成・更新リクエスト型 */
export interface VillageCategoryRequest {
  name: string
  parentId: string | null
  displayOrder: number | null
}
