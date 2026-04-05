import type {
  TaxSettingResponse,
  CreateTaxSettingRequest,
  UpdateTaxSettingRequest,
} from '~/types/tax-setting'

const BASE = '/api/v1/system-admin/tax-settings'

export function useTaxSettingApi() {
  const api = useApi()

  async function getTaxSettings() {
    return api<{ data: TaxSettingResponse[] }>(BASE)
  }

  async function getTaxSetting(id: number) {
    return api<{ data: TaxSettingResponse }>(`${BASE}/${id}`)
  }

  async function createTaxSetting(body: CreateTaxSettingRequest) {
    return api<{ data: TaxSettingResponse }>(BASE, {
      method: 'POST',
      body,
    })
  }

  async function updateTaxSetting(id: number, body: UpdateTaxSettingRequest) {
    return api<{ data: TaxSettingResponse }>(`${BASE}/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTaxSetting(id: number) {
    return api(`${BASE}/${id}`, { method: 'DELETE' })
  }

  return {
    getTaxSettings,
    getTaxSetting,
    createTaxSetting,
    updateTaxSetting,
    deleteTaxSetting,
  }
}
