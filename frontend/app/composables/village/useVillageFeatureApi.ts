import type { components } from '~/types/generated'
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

// 村紋 (#2355) は生成型を正とする（手書きの嘘型を作らない）
type MonshoUploadUrlRequest = components['schemas']['MonshoUploadUrlRequest']
type MonshoUploadUrlResponse = components['schemas']['MonshoUploadUrlResponse']
type VillageMonshoResponse = components['schemas']['VillageMonshoResponse']

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
  // Phase 2: 村紋 (VillageMonshoController) — /api/v1/villages/{villageId}/monsho
  //
  // #2355 で BE にプリサインド発行 EP（POST /monsho/upload-url）が新設された。
  // これにより「presign 発行 → R2 へ直 PUT → PUT /monsho で r2Key 登録」の3ステップで
  // ファイル入稿できる。かつての FormData を POST /monsho に送る `uploadMonsho`（実測 405・
  // 死蔵）は撤去済みで、本実装はそれとは別物（multipart は一切使わない）。
  // 読取（表示）は生 r2Key ＋ FE の公開 URL 化（buildR2Url）が正で、BE は署名 URL 化しない。
  // =====================================================================

  /**
   * §13.2 村紋アップロード用 presigned PUT URL を発行する。
   * POST /api/v1/villages/{villageId}/monsho/upload-url
   */
  async function generateMonshoUploadUrl(
    villageId: string,
    body: MonshoUploadUrlRequest,
  ): Promise<MonshoUploadUrlResponse> {
    const res = await api<{ data: MonshoUploadUrlResponse }>(
      `/api/v1/villages/${villageId}/monsho/upload-url`,
      { method: 'POST', body },
    )
    return res.data
  }

  /**
   * §13.2 村紋 r2Key を DB に登録する（アップロード完了後）。
   * PUT /api/v1/villages/{villageId}/monsho
   */
  async function updateMonsho(
    villageId: string,
    r2Key: string,
  ): Promise<VillageMonshoResponse> {
    const res = await api<{ data: VillageMonshoResponse }>(
      `/api/v1/villages/${villageId}/monsho`,
      { method: 'PUT', body: { r2Key } },
    )
    return res.data
  }

  /**
   * §13.2 村紋を削除する。
   * DELETE /api/v1/villages/{villageId}/monsho
   */
  async function deleteMonsho(villageId: string): Promise<VillageMonshoResponse> {
    const res = await api<{ data: VillageMonshoResponse }>(
      `/api/v1/villages/${villageId}/monsho`,
      { method: 'DELETE' },
    )
    return res.data
  }

  /**
   * 村紋ファイル入稿のフルフロー（useProfileMediaApi#uploadAndCommit を手本）。
   * 1. generateMonshoUploadUrl() で presigned PUT URL を発行
   * 2. fetch() で R2 に直接 PUT（api ラッパーではなく生 fetch）
   * 3. updateMonsho() で DB に r2Key を登録
   *
   * @returns 登録後の村紋レスポンス（monshoR2Key を含む）
   */
  async function uploadMonsho(
    villageId: string,
    file: File,
    onProgress?: (progress: number) => void,
  ): Promise<VillageMonshoResponse> {
    // 1. presign 発行
    const presign = await generateMonshoUploadUrl(villageId, {
      contentType: file.type,
      fileSize: file.size,
    })
    if (!presign.uploadUrl || !presign.r2Key) {
      throw new Error('presign レスポンスに uploadUrl / r2Key がありません')
    }

    // 2. R2 に直接 PUT
    const r2Response = await fetch(presign.uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': file.type },
    })
    if (!r2Response.ok) {
      throw new Error(`R2 アップロード失敗: ${r2Response.status} ${r2Response.statusText}`)
    }
    onProgress?.(100)

    // 3. r2Key を DB 登録
    return updateMonsho(villageId, presign.r2Key)
  }

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
    // Phase 2: 村紋 (#2355)
    generateMonshoUploadUrl,
    updateMonsho,
    deleteMonsho,
    uploadMonsho,
  }
}
