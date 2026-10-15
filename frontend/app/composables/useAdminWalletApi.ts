/**
 * F18 Phase 4 第三陣 S3 — SystemAdmin 専用 同義語管理 API クライアント。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §7.6
 *
 * <p>運営マスタの fuzzy match キャッシュに同義語を登録・編集・削除する。
 * 組織 ADMIN は触れない（運営ポリシー）。
 */

/** 同義語レスポンス DTO（バックエンド SynonymResponse と整合）。 */
export interface SynonymItem {
  id: string
  providerId: string
  providerDisplayName: string | null
  synonymDisplay: string
  synonymNormalized: string
  memo: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateSynonymBody {
  providerId: string
  synonymDisplay: string
  memo?: string | null
}

export interface UpdateSynonymBody {
  synonymDisplay?: string
  memo?: string | null
}

const BASE = '/api/v1/admin/point-cards/synonyms'

export function useAdminWalletApi() {
  const api = useApi()

  function listSynonyms(providerId?: string) {
    const path = providerId ? `${BASE}?providerId=${encodeURIComponent(providerId)}` : BASE
    return api<{ data: SynonymItem[] }>(path)
  }

  function createSynonym(body: CreateSynonymBody) {
    return api<{ data: SynonymItem }>(BASE, { method: 'POST', body })
  }

  function updateSynonym(id: string, body: UpdateSynonymBody) {
    return api<{ data: SynonymItem }>(`${BASE}/${id}`, { method: 'PATCH', body })
  }

  function deleteSynonym(id: string) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  return {
    listSynonyms,
    createSynonym,
    updateSynonym,
    deleteSynonym,
  }
}
