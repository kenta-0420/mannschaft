import type {
  CreateTermRequest,
  GenerateHandoverPackRequest,
  HandoverPack,
  HandoverPackDownloadResponse,
  MemberTerm,
} from '~/types/repairPlanHandover'

/**
 * F08.8 Phase 5 申し送りパック API クライアント。
 *
 * <p>設計書 docs/features/F08.8_repair_longterm_dashboard.md Phase 5 に準拠。</p>
 */
export function useHandoverPackApi(scopeType: string, scopeId: string) {
  const api = useApi()
  const BASE = `/api/v1/${scopeType}/${scopeId}/repair-plan/handover-packs`

  /**
   * 申し送りパックを生成する（非同期生成: 即座に GENERATING 状態で返る）。
   */
  async function generatePack(req: GenerateHandoverPackRequest): Promise<HandoverPack> {
    const res = await api<{ data: HandoverPack }>(BASE, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  /**
   * 申し送りパック一覧を取得する（最新順、最大 50 件）。
   */
  async function listPacks(): Promise<HandoverPack[]> {
    const res = await api<{ data: HandoverPack[] }>(BASE)
    return res.data
  }

  /**
   * 署名付きダウンロード URL を取得する。
   * URL は短時間しか有効でないため、取得直後に新タブを開くこと。
   */
  async function getDownloadUrl(packId: string): Promise<HandoverPackDownloadResponse> {
    const res = await api<{ data: HandoverPackDownloadResponse }>(`${BASE}/${packId}/download-url`)
    return res.data
  }

  /**
   * 申し送りパックを削除する（ADMIN 専用）。
   */
  async function deletePack(packId: string): Promise<void> {
    await api(`${BASE}/${packId}`, { method: 'DELETE' })
  }

  return { generatePack, listPacks, getDownloadUrl, deletePack }
}

/**
 * 理事任期 API クライアント。
 */
export function useTermApi(teamId: string) {
  const api = useApi()
  const BASE = `/api/v1/teams/${teamId}/member-terms`

  /**
   * 任期を作成する（ADMIN 専用）。
   */
  async function createTerm(req: CreateTermRequest): Promise<MemberTerm> {
    const res = await api<{ data: MemberTerm }>(BASE, {
      method: 'POST',
      body: req,
    })
    return res.data
  }

  /**
   * 任期一覧を取得する（在任中→開始日降順）。
   */
  async function listTerms(): Promise<MemberTerm[]> {
    const res = await api<{ data: MemberTerm[] }>(BASE)
    return res.data
  }

  /**
   * 任期を削除する（ADMIN 専用）。
   */
  async function deleteTerm(termId: number): Promise<void> {
    await api(`${BASE}/${termId}`, { method: 'DELETE' })
  }

  return { createTerm, listTerms, deleteTerm }
}
