import type {
  PinListResponse,
  PinOrderUpdateRequest,
  PinResponse,
  PostingIdentityListResponse,
  ReportCreateRequest,
  ReportListParams,
  ReportResolveRequest,
  ReportResponse,
  VillageNicknameResponse,
  VillageNicknameUpdateRequest,
  VillageRepresentativeGrantRequest,
  VillageRepresentativeResponse,
  VillageRepresentativeRevokeRequest,
} from '~/types/village'

/**
 * F17.1 村機能 API composable — ニックネーム・投稿主体・ピン・通報・代表委任・村紋
 *
 * Backend Controller: backend/src/main/java/com/mannschaft/app/village/controller/*.java
 * 設計書: docs/features/F17.1_village_community.md §4.7 / §4.8 / §4.9 / §4.11 / §3.11 / §13.2
 */
export function useVillageFeatureApi() {
  const api = useApi()

  // クエリ文字列ヘルパー
  function qs(params?: object | null): string {
    if (!params) return ''
    const u = new URLSearchParams()
    for (const [k, v] of Object.entries(params as Record<string, unknown>)) {
      if (v !== undefined && v !== null && v !== '') u.set(k, String(v))
    }
    const s = u.toString()
    return s ? `?${s}` : ''
  }

  // =====================================================================
  // ニックネーム (VillageNicknameController) — /api/v1/me/village-nickname
  // =====================================================================

  /** §4.7.1 取得 */
  async function getMyNickname() {
    const res = await api<{ data: VillageNicknameResponse }>('/api/v1/me/village-nickname')
    return res.data
  }

  /** §4.7.2 更新 */
  async function updateNickname(body: VillageNicknameUpdateRequest) {
    const res = await api<{ data: VillageNicknameResponse }>('/api/v1/me/village-nickname', {
      method: 'PUT',
      body,
    })
    return res.data
  }

  // =====================================================================
  // 投稿主体 (PostingIdentityController)
  // /api/v1/me/villages/{villageId}/posting-identities
  // =====================================================================

  /** §4.8 投稿可能な主体一覧 */
  async function listPostingIdentities(villageId: string) {
    const res = await api<{ data: PostingIdentityListResponse }>(
      `/api/v1/me/villages/${villageId}/posting-identities`,
    )
    return res.data
  }

  // =====================================================================
  // ピン (VillagePinController) — /api/v1/me/village-pins
  // =====================================================================

  /** §4.9.1 一覧 */
  async function listPins() {
    const res = await api<{ data: PinListResponse }>('/api/v1/me/village-pins')
    return res.data
  }

  /** §4.9.2 追加 */
  async function addPin(villageId: string) {
    const res = await api<{ data: PinResponse }>(`/api/v1/me/village-pins/${villageId}`, { method: 'POST' })
    return res.data
  }

  /** §4.9.3 解除 */
  async function removePin(villageId: string) {
    return api(`/api/v1/me/village-pins/${villageId}`, { method: 'DELETE' })
  }

  /** §4.9.4 並び替え */
  async function updatePinOrder(body: PinOrderUpdateRequest) {
    const res = await api<{ data: PinListResponse }>('/api/v1/me/village-pins/order', { method: 'PATCH', body })
    return res.data
  }

  // =====================================================================
  // 通報 (VillageReportController) — /api/v1/villages/{villageId}/reports
  // =====================================================================

  /** §4.11.1 通報送信 */
  async function createReport(villageId: string, body: ReportCreateRequest) {
    const res = await api<{ data: ReportResponse }>(`/api/v1/villages/${villageId}/reports`, {
      method: 'POST',
      body,
    })
    return res.data
  }

  /** §4.11.2 通報一覧（村長/長老/運営向け） */
  async function listReports(villageId: string, params?: ReportListParams) {
    const res = await api<{ data: ReportResponse[] }>(
      `/api/v1/villages/${villageId}/reports${qs(params)}`,
    )
    return res.data
  }

  /** §4.11.3 通報解決 */
  async function resolveReport(villageId: string, reportId: string, body: ReportResolveRequest) {
    const res = await api<{ data: ReportResponse }>(
      `/api/v1/villages/${villageId}/reports/${reportId}/resolve`,
      { method: 'POST', body },
    )
    return res.data
  }

  // =====================================================================
  // Phase 2: 代表委任 (VillageRepresentativeController)
  // /api/v1/villages/{villageId}/representatives
  //
  // かつて「Backend Controller は未実装のため URL は推測」というコメントが
  // 置かれていたが、`VillageRepresentativeController` は実装済みであり誤りのため撤去した。
  //
  // BE の実契約（`VillageRepresentativeController` 実物で裏取り済み）:
  //   GET    /representatives                    （?includeRevoked=false）
  //   POST   /representatives                    → 201
  //   DELETE /representatives/{representativeId} （body は `@RequestBody(required=false)` で任意）
  // =====================================================================

  /** 代表委任一覧 */
  async function listRepresentatives(villageId: string) {
    const res = await api<{ data: VillageRepresentativeResponse[] }>(
      `/api/v1/villages/${villageId}/representatives`,
    )
    return res.data
  }

  /** 代表委任を発行 */
  async function grantRepresentative(
    villageId: string,
    body: VillageRepresentativeGrantRequest,
  ) {
    const res = await api<{ data: VillageRepresentativeResponse }>(
      `/api/v1/villages/${villageId}/representatives`,
      { method: 'POST', body },
    )
    return res.data
  }

  /**
   * 代表委任を取消
   *
   * BE は `POST /representatives/{id}/revoke` ではなく
   * `DELETE /representatives/{representativeId}`（`VillageRepresentativeController#revoke`）。
   * 取消理由メモ（`{note}`・200 文字以内）は `@RequestBody(required = false)` で任意のため、
   * 指定が無いときは body を付けずに送る。
   */
  async function revokeRepresentative(
    villageId: string,
    id: string,
    body?: VillageRepresentativeRevokeRequest,
  ) {
    const res = await api<{ data: VillageRepresentativeResponse }>(
      `/api/v1/villages/${villageId}/representatives/${id}`,
      body ? { method: 'DELETE', body } : { method: 'DELETE' },
    )
    return res.data
  }

  // =====================================================================
  // Phase 2: 村紋 (VillageMonshoController)
  //
  // 村紋の「アップロード」関数はここには置かない。
  // BE は multipart を一切受け取らず（`PUT /monsho` に JSON `{r2Key}` を渡す契約のみ）、
  // R2 への実体アップロードは「別経路のプリサインド URL 発行 API」に委ねる設計だが、
  // その委ね先が村ドメインに存在しない（2026-07-15 時点で BE 側の欠落）。
  // かつて存在した `uploadMonsho` は `POST /monsho` に FormData を送る実装で、
  // 実測 405・呼び出し元ゼロの死蔵コードだったため撤去した。
  // 村紋アップロード UI は BE のプリサインド発行エンドポイント新設が前提となる。
  // =====================================================================

  return {
    // ニックネーム
    getMyNickname,
    updateNickname,
    // 投稿主体
    listPostingIdentities,
    // ピン
    listPins,
    addPin,
    removePin,
    updatePinOrder,
    // 通報
    createReport,
    listReports,
    resolveReport,
    // Phase 2: 代表委任
    listRepresentatives,
    grantRepresentative,
    revokeRepresentative,
  }
}
