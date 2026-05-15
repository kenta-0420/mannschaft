/**
 * F18 個人ポイントカードウォレット — API クライアント composable。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §6
 *
 * <p>14 本のエンドポイントを束ねるクライアント。Spring Boot 側は ApiResponse.of() で
 * すべてのレスポンスを {@code { data: T }} の形にラップしているため、
 * 各メソッドは内部で `.data` を剥がして使いやすい型を返す。</p>
 *
 * <p>認証トークン付与・401 → refresh ローテーション・5xx トースト集約は {@code useApi}
 * （ofetch ラッパー）が自動で行う。エラーは ofetch の FetchError を投げるため、
 * 呼び出し側で try-catch して errorCode を見て分岐する。</p>
 */
import type {
  CreateGroupRequest,
  CreateUserPointCardRequest,
  PointCardGroupDetail,
  PointCardGroupListItem,
  PointCardProvider,
  PointCardUserSettings,
  UpdateGroupRequest,
  UpdateUserPointCardRequest,
  UpdateUserSettingsRequest,
  UserPointCardDetail,
  UserPointCardListItem,
} from '~/types/pointCard'

const BASE = '/api/v1/point-cards'

export function useWalletApi() {
  const api = useApi()

  // ─────────────────────────────────────────────
  // Providers
  // ─────────────────────────────────────────────

  /**
   * 有効化されているプロバイダー一覧を取得する。
   * カード追加フォームのプリセットボタン表示と、所持カードのロゴ補強に使う。
   */
  async function listProviders(): Promise<PointCardProvider[]> {
    const res = await api<{ data: PointCardProvider[] }>(`${BASE}/providers`)
    return res.data
  }

  // ─────────────────────────────────────────────
  // Cards
  // ─────────────────────────────────────────────

  /**
   * 自分のカード一覧を取得する。barcodeValue / nickname / memo は含まれない。
   * favorite → displayOrder → createdAt 降順の順で並ぶ。
   */
  async function listCards(): Promise<UserPointCardListItem[]> {
    const res = await api<{ data: UserPointCardListItem[] }>(BASE)
    return res.data
  }

  /**
   * カード詳細を取得する。提示モードで使う復号値を含めて返却される。
   * バックエンド側で `findByIdAndUserId` を呼ぶため、他人のカード ID は 404 となる。
   */
  async function getCard(id: string): Promise<UserPointCardDetail> {
    const res = await api<{ data: UserPointCardDetail }>(`${BASE}/${id}`)
    return res.data
  }

  /**
   * カードを新規追加する。`providerId` はサーバー側 fuzzy match で自動解決される。
   * 保有上限 200 枚超過時は 409 + `POINT_CARD_003`。
   */
  async function createCard(body: CreateUserPointCardRequest): Promise<UserPointCardDetail> {
    const res = await api<{ data: UserPointCardDetail }>(BASE, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /**
   * カードを部分更新する。`displayName` を変えるとサーバー側で `providerId` を再 fuzzy match する。
   * `barcodeValue` / `barcodeFormat` は変更不可（仕様）。
   */
  async function updateCard(
    id: string,
    body: UpdateUserPointCardRequest,
  ): Promise<UserPointCardDetail> {
    const res = await api<{ data: UserPointCardDetail }>(`${BASE}/${id}`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  /** カードを物理削除する。監査ログ POINT_CARD_DELETED が記録される。 */
  async function deleteCard(id: string): Promise<void> {
    await api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  /**
   * カード利用記録（`last_used_at` 更新）。提示モードを閉じた直後に背景で呼ぶ。
   * レート 600/h・監査ログは記録しない（呼び出し頻度高のため）。
   */
  async function recordUsed(id: string): Promise<void> {
    await api(`${BASE}/${id}/used`, { method: 'POST' })
  }

  // ─────────────────────────────────────────────
  // Groups
  // ─────────────────────────────────────────────

  /** 自分のグループ一覧を取得する。カード件数のみ含む軽量版。 */
  async function listGroups(): Promise<PointCardGroupListItem[]> {
    const res = await api<{ data: PointCardGroupListItem[] }>(`${BASE}/groups`)
    return res.data
  }

  /** グループ詳細を取得する。提示モード起動とは別経路（監査ログ非記録）。 */
  async function getGroup(id: string): Promise<PointCardGroupDetail> {
    const res = await api<{ data: PointCardGroupDetail }>(`${BASE}/groups/${id}`)
    return res.data
  }

  /**
   * グループを作成する。最大 20 枚 / 50 グループ上限。
   * cardIds 未指定で空グループ作成可能（後で PATCH で追加できる）。
   */
  async function createGroup(body: CreateGroupRequest): Promise<PointCardGroupDetail> {
    const res = await api<{ data: PointCardGroupDetail }>(`${BASE}/groups`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /**
   * グループを部分更新する。cardIds を指定すると既存アイテムを差し替え（追加だけでなく削除も含む）。
   */
  async function updateGroup(
    id: string,
    body: UpdateGroupRequest,
  ): Promise<PointCardGroupDetail> {
    const res = await api<{ data: PointCardGroupDetail }>(`${BASE}/groups/${id}`, {
      method: 'PATCH',
      body,
    })
    return res.data
  }

  /** グループを削除する（カード本体は残る）。監査ログ POINT_CARD_GROUP_DELETED 記録。 */
  async function deleteGroup(id: string): Promise<void> {
    await api(`${BASE}/groups/${id}`, { method: 'DELETE' })
  }

  /**
   * 提示モードを開始する（グループ詳細を返しつつ POINT_CARD_VIEWED 監査ログを 1 件記録）。
   * 個別カード表示では発火しない（設計書 §11.3 整合）。
   */
  async function startPresentation(id: string): Promise<PointCardGroupDetail> {
    const res = await api<{ data: PointCardGroupDetail }>(
      `${BASE}/groups/${id}/presentation-start`,
      { method: 'POST' },
    )
    return res.data
  }

  // ─────────────────────────────────────────────
  // Settings
  // ─────────────────────────────────────────────

  /** ユーザー設定を取得する。初回アクセス時は isEnabled=false かつ termsAcceptedAt=null。 */
  async function getSettings(): Promise<PointCardUserSettings> {
    const res = await api<{ data: PointCardUserSettings }>(`${BASE}/settings`)
    return res.data
  }

  /**
   * ユーザー設定を更新する。termsVersion を送ると termsAcceptedAt が現在時刻で更新される。
   * 規約同意フローはここで isEnabled=true + termsVersion=CURRENT_TERMS_VERSION を送る。
   */
  async function updateSettings(
    body: UpdateUserSettingsRequest,
  ): Promise<PointCardUserSettings> {
    const res = await api<{ data: PointCardUserSettings }>(`${BASE}/settings`, {
      method: 'PUT',
      body,
    })
    return res.data
  }

  return {
    // Providers
    listProviders,
    // Cards
    listCards,
    getCard,
    createCard,
    updateCard,
    deleteCard,
    recordUsed,
    // Groups
    listGroups,
    getGroup,
    createGroup,
    updateGroup,
    deleteGroup,
    startPresentation,
    // Settings
    getSettings,
    updateSettings,
  }
}
