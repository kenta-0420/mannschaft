import type { components } from '~/types/generated'

type Schemas = components['schemas']

export type EffortTemplateSettings = Schemas['EffortTemplateSettingsResponse']
export type EffortFieldSetting = Schemas['FieldSetting']
export type UpdateEffortTemplateSettingsBody = Schemas['UpdateEffortTemplateSettingsRequest']

/**
 * F06.4.1 チームテンプレートの「頑張り記録の指標」設定 API
 * （`/api/v1/teams/{teamId}/effort-records/template-settings/{templateId}`）。
 * teamId は数値の team ID（activity テンプレートの scopeId）。PUT は全 field 設定の全置換で version 必須。
 */
export function useEffortTemplateSettingsApi() {
  const api = useApi()

  function path(teamId: number, templateId: number) {
    return `/api/v1/teams/${teamId}/effort-records/template-settings/${templateId}`
  }

  function get(teamId: number, templateId: number) {
    return api<{ data: EffortTemplateSettings }>(path(teamId, templateId))
  }

  function update(teamId: number, templateId: number, body: UpdateEffortTemplateSettingsBody) {
    return api<{ data: EffortTemplateSettings }>(path(teamId, templateId), { method: 'PUT', body })
  }

  return { get, update }
}
