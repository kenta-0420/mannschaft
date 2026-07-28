import type { components } from '~/types/generated'

/**
 * F17.3 村憲章（Village Charter）API composable
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/VillageCharterController.java
 * 設計書: docs/features/F17.3_village_charter.md §9.2 / §18（8 EP・req/res 契約）
 *
 * # 型は生成型（types/generated）を使う
 *  CLAUDE.md「新規 API の型は生成型を優先して使用し、既存の手動型は段階的に移行する」に従う
 *  （`useVillageRecruitCategoryApi.ts` と同方針）。
 *
 * # version 同送は PUT（層1）と PATCH order（層2）のみ【§6.3・§7・🔴B 殿裁定】
 *  POST/DELETE（articles・drafters）は version を同送しない。サーバ側の親 charter 行の悲観ロック
 *  （`SELECT FOR UPDATE`）で直列化されるため 409 を返さず常に成功する。
 *
 * # 構造変更系は更新後の憲章全体を返す【§18.2・🟡I 精査】
 *  POST/DELETE/PATCH order（articles・drafters・revisions）は version・採番・策定者連番が
 *  一括で動くため、単票 DTO ではなく `VillageCharterResponse` 全体を返す。
 *  単票を返すのは `PUT articles/{id}`（編集した条の新 version が載れば足りる）のみ。
 */

export type VillageCharterResponse = components['schemas']['VillageCharterResponse']
export type CharterArticleResponse = components['schemas']['CharterArticleResponse']
export type CharterDrafterResponse = components['schemas']['CharterDrafterResponse']
export type CharterRevisionResponse = components['schemas']['CharterRevisionResponse']
export type CharterArticleCreateRequest = components['schemas']['CharterArticleCreateRequest']
export type CharterArticleUpdateRequest = components['schemas']['CharterArticleUpdateRequest']
export type CharterArticleOrderUpdateRequest = components['schemas']['CharterArticleOrderUpdateRequest']
export type CharterDrafterCreateRequest = components['schemas']['CharterDrafterCreateRequest']
export type CharterRevisionCreateRequest = components['schemas']['CharterRevisionCreateRequest']

export function useVillageCharterApi() {
  const api = useApi()

  const base = (villageId: string) => `/api/v1/villages/${villageId}/charter`

  /**
   * 村憲章を取得する（read 公開ゲート・§3.2）。
   * 未制定の村でも 200（`hasCharter=false`・§12.2）。UNLISTED 非メンバー・不存在・凍結村は 404。
   */
  async function getCharter(villageId: string): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(base(villageId))
    return res.data
  }

  /**
   * 条を末尾に追加する（現役 HEADMAN/ELDER・§4.4）。
   * version 非同送（悲観ロック直列化・409 なし）。初回は憲章が自動生成される。
   */
  async function createArticle(
    villageId: string,
    body: CharterArticleCreateRequest,
  ): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(`${base(villageId)}/articles`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /**
   * 条の本文/付則を更新する（層1 条単位楽観ロック・version 同送必須）。
   * 競合時は `CHARTER_ARTICLE_VERSION_CONFLICT`（409）。
   */
  async function updateArticle(
    villageId: string,
    articleId: string,
    body: CharterArticleUpdateRequest,
  ): Promise<CharterArticleResponse> {
    const res = await api<{ data: CharterArticleResponse }>(
      `${base(villageId)}/articles/${articleId}`,
      { method: 'PUT', body },
    )
    return res.data
  }

  /**
   * 条を論理削除し残る条を再連番する（悲観ロック直列化・version 非同送・409 なし）。
   */
  async function deleteArticle(villageId: string, articleId: string): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(
      `${base(villageId)}/articles/${articleId}`,
      { method: 'DELETE' },
    )
    return res.data
  }

  /**
   * 条の並び順を一括更新する（層2 憲章全体楽観ロック・`charterVersion` 同送必須）。
   * 競合時は `CHARTER_ORDER_VERSION_CONFLICT`（409）。集合不一致は 400。
   */
  async function reorderArticles(
    villageId: string,
    body: CharterArticleOrderUpdateRequest,
  ): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(`${base(villageId)}/articles/order`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  /** 策定者を追加する（村ニックネームをサーバが焼付・§5.2）。重複は `CHARTER_DRAFTER_DUPLICATE`（409）。 */
  async function addDrafter(
    villageId: string,
    body: CharterDrafterCreateRequest,
  ): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(`${base(villageId)}/drafters`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /** 策定者を削除する（更新後の憲章全体を返す・再連番・AC-16b）。 */
  async function removeDrafter(villageId: string, drafterId: string): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(
      `${base(villageId)}/drafters/${drafterId}`,
      { method: 'DELETE' },
    )
    return res.data
  }

  /**
   * 「改正を確定」= 改定日（`last_revised_at`）を更新し改定履歴に 1 行追記する（§8.2）。
   * 条文の公開状態は変えない非ゲート操作（保存＝即時公開はこの前の各操作で既に反映済み）。
   */
  async function addRevision(
    villageId: string,
    body?: CharterRevisionCreateRequest,
  ): Promise<VillageCharterResponse> {
    const res = await api<{ data: VillageCharterResponse }>(`${base(villageId)}/revisions`, {
      method: 'POST',
      body: body ?? {},
    })
    return res.data
  }

  return {
    getCharter,
    createArticle,
    updateArticle,
    deleteArticle,
    reorderArticles,
    addDrafter,
    removeDrafter,
    addRevision,
  }
}
