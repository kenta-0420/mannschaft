import type { components } from '~/types/generated'

/**
 * F17.1 P4 村機能 API composable — 村ごと募集カテゴリマスタ（CRUD ＋ 並び替え）
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/VillageRecruitCategoryController.java
 * 設計書: docs/features/F17.1_village_headman_console_and_recruit_categories.md §6.1
 *
 * # 型は生成型（types/generated）を使う
 *  CLAUDE.md「新規 API の型は生成型を優先して使用し、既存の手動型は段階的に移行する」に従う。
 *  村ドメインの既存 composable は手動型（`~/types/village`）を使っているが、**本 API は新規のため
 *  生成型を採る**。P3 の実機検証で発見した課題D（ニュースレター設定の FE 型が実 API と食い違い
 *  保存が必ず 400 になる／設計書 §3.4）のとおり、**手動型は BE と静かにドリフトして嘘をつく**。
 *  生成型は `docs/openapi.json` 由来なのでこのクラスの事故が構造的に起きない。
 *
 * # 生成型のフィールドが全て optional な件
 *  BE に `@Schema(requiredMode = REQUIRED)` が未整備なため openapi-typescript が全フィールドを
 *  optional で吐く（memory `project_generated_type_migration_blocked`）。呼び出し側で
 *  `?? ''` / `?? 0` の既定値を当てて吸収する。**嘘の非 null アサーションはしない。**
 */

/** カテゴリ応答（生成型のエイリアス）。 */
export type VillageRecruitCategory = components['schemas']['VillageRecruitCategoryResponse']
/** カテゴリ作成リクエスト。 */
export type VillageRecruitCategoryCreateRequest =
  components['schemas']['VillageRecruitCategoryCreateRequest']
/** カテゴリ更新リクエスト（部分更新・null は変更なし）。 */
export type VillageRecruitCategoryUpdateRequest =
  components['schemas']['VillageRecruitCategoryUpdateRequest']
/** 一括並び替えリクエスト。 */
export type VillageRecruitCategoryOrderRequest =
  components['schemas']['VillageRecruitCategoryOrderRequest']

export function useVillageRecruitCategoryApi() {
  const api = useApi()

  const base = (villageId: string) => `/api/v1/villages/${villageId}/recruit-categories`

  /**
   * §6.1 #1 カテゴリ一覧（村人・display_order 昇順・論理削除除く）。
   *
   * BE は `ApiResponse.of(List<...>)` で `{data: [...]}` のエンベロープを返す
   * （`VillageRecruitCategoryController#list`）。
   */
  async function listCategories(villageId: string): Promise<VillageRecruitCategory[]> {
    const res = await api<{ data: VillageRecruitCategory[] }>(base(villageId))
    return res.data
  }

  /** §6.1 #2 作成（村長/長老・201）。 */
  async function createCategory(
    villageId: string,
    body: VillageRecruitCategoryCreateRequest,
  ): Promise<VillageRecruitCategory> {
    const res = await api<{ data: VillageRecruitCategory }>(base(villageId), {
      method: 'POST',
      body,
    })
    return res.data
  }

  /** §6.1 #3 更新（村長/長老）。 */
  async function updateCategory(
    villageId: string,
    categoryId: string,
    body: VillageRecruitCategoryUpdateRequest,
  ): Promise<VillageRecruitCategory> {
    const res = await api<{ data: VillageRecruitCategory }>(`${base(villageId)}/${categoryId}`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  /** §6.1 #4 論理削除（村長/長老・204）。使用中は VILLAGE_086。 */
  async function deleteCategory(villageId: string, categoryId: string): Promise<void> {
    await api(`${base(villageId)}/${categoryId}`, { method: 'DELETE' })
  }

  /**
   * §6.1 #5 一括並び替え（村長/長老）。
   *
   * `orderedCategoryIds` の並び順どおりに `display_order` を 10 刻みで振り直した結果が返る。
   */
  async function reorderCategories(
    villageId: string,
    orderedCategoryIds: string[],
  ): Promise<VillageRecruitCategory[]> {
    const res = await api<{ data: VillageRecruitCategory[] }>(`${base(villageId)}/order`, {
      method: 'PUT',
      body: { orderedCategoryIds } satisfies VillageRecruitCategoryOrderRequest,
    })
    return res.data
  }

  return {
    listCategories,
    createCategory,
    updateCategory,
    deleteCategory,
    reorderCategories,
  }
}
