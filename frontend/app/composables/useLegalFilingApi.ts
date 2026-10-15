import type {
  CreateLegalFilingRequest,
  EvidenceDownloadUrlResponse,
  LegalFiling,
} from '~/types/succession'

/**
 * 法的手続き準備 API クライアント（F09.15 S6-B）。
 *
 * 関連エンドポイント:
 * - GET    /api/v1/organizations/{orgId}/succession/legal-filings
 * - GET    /api/v1/organizations/{orgId}/succession/legal-filings/by-resident/{residentRegistryId}
 * - POST   /api/v1/organizations/{orgId}/succession/legal-filings
 * - GET    /api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}
 * - POST   /api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}/evidence-package
 * - GET    /api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}/evidence-package/download-url
 */
export function useLegalFilingApi() {
  const api = useApi()

  async function listByOrganization(orgId: string) {
    return api<{ data: LegalFiling[] }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings`,
    )
  }

  async function listByResident(orgId: string, residentRegistryId: number) {
    return api<{ data: LegalFiling[] }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings/by-resident/${residentRegistryId}`,
    )
  }

  async function createLegalFiling(orgId: string, body: CreateLegalFilingRequest) {
    return api<{ data: LegalFiling }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings`,
      { method: 'POST', body },
    )
  }

  async function getById(orgId: string, legalFilingId: string) {
    return api<{ data: LegalFiling }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings/${legalFilingId}`,
    )
  }

  async function buildEvidencePackage(orgId: string, legalFilingId: string) {
    return api<{ data: LegalFiling }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings/${legalFilingId}/evidence-package`,
      { method: 'POST' },
    )
  }

  async function getEvidenceDownloadUrl(orgId: string, legalFilingId: string) {
    return api<{ data: EvidenceDownloadUrlResponse }>(
      `/api/v1/organizations/${orgId}/succession/legal-filings/${legalFilingId}/evidence-package/download-url`,
    )
  }

  return {
    listByOrganization,
    listByResident,
    createLegalFiling,
    getById,
    buildEvidencePackage,
    getEvidenceDownloadUrl,
  }
}
