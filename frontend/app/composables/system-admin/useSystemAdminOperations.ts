import type {
  FeatureFlagResponse,
  MaintenanceScheduleResponse,
  CreateMaintenanceScheduleRequest,
  UpdateMaintenanceScheduleRequest,
  ModuleResponse,
  UpdateModulePaidPlanRequest,
  UpdateModuleActiveRequest,
  BatchJobLogResponse,
  BetaRestrictionConfigResponse,
  UpdateBetaRestrictionRequest,
} from '~/types/system-admin'

const BASE = '/api/v1/system-admin'

/**
 * システム管理者向け運用・保守 API。
 * 取り扱う対象: 機能フラグ / メンテナンススケジュール / モジュール管理 / バッチログ / ベータ制限。
 */
export function useSystemAdminOperations() {
  const api = useApi()

  // ===== Feature Flags =====
  async function getFeatureFlags() {
    return api<{ data: FeatureFlagResponse[] }>(`${BASE}/feature-flags`)
  }

  async function updateFeatureFlag(
    flagKey: string,
    body: { isEnabled: boolean; description?: string },
  ) {
    return api<{ data: FeatureFlagResponse }>(`${BASE}/feature-flags/${flagKey}`, {
      method: 'PUT',
      body,
    })
  }

  // ===== Maintenance Schedules =====
  async function getMaintenanceSchedules() {
    return api<{ data: MaintenanceScheduleResponse[] }>(`${BASE}/maintenance-schedules`)
  }

  async function getMaintenanceSchedule(id: number) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules/${id}`)
  }

  async function createMaintenanceSchedule(body: CreateMaintenanceScheduleRequest) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules`, {
      method: 'POST',
      body,
    })
  }

  async function updateMaintenanceSchedule(id: number, body: UpdateMaintenanceScheduleRequest) {
    return api<{ data: MaintenanceScheduleResponse }>(`${BASE}/maintenance-schedules/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}`, { method: 'DELETE' })
  }

  async function activateMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}/activate`, { method: 'POST' })
  }

  async function completeMaintenanceSchedule(id: number) {
    return api(`${BASE}/maintenance-schedules/${id}/complete`, { method: 'PATCH' })
  }

  // ===== Modules =====
  async function getModules() {
    return api<{ data: ModuleResponse[] }>(`${BASE}/modules`)
  }

  async function getModule(id: number) {
    return api<{ data: ModuleResponse }>(`${BASE}/modules/${id}`)
  }

  async function updateModuleLevelAvailability(id: number, body: Record<string, unknown>) {
    return api(`${BASE}/modules/${id}/level-availability`, { method: 'PATCH', body })
  }

  async function updateModulePaidPlan(id: number, body: UpdateModulePaidPlanRequest) {
    return api(`${BASE}/modules/${id}/paid-plan`, { method: 'PATCH', body })
  }

  async function updateModuleActive(id: number, body: UpdateModuleActiveRequest) {
    return api(`${BASE}/modules/${id}/active`, { method: 'PATCH', body })
  }

  // ===== Batch Logs =====
  async function getBatchLogs(params?: { page?: number; size?: number; jobName?: string }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    if (params?.jobName) query.set('jobName', params.jobName)
    return api<{ data: BatchJobLogResponse[] }>(`${BASE}/batch-logs?${query}`)
  }

  // ===== Beta Restriction (F00.6) =====
  async function getBetaRestrictionConfig() {
    return api<{ data: BetaRestrictionConfigResponse }>(`${BASE}/beta-restriction`)
  }

  async function updateBetaRestrictionConfig(body: UpdateBetaRestrictionRequest) {
    return api<{ data: BetaRestrictionConfigResponse }>(`${BASE}/beta-restriction`, {
      method: 'PUT',
      body,
    })
  }

  return {
    getFeatureFlags,
    updateFeatureFlag,
    getMaintenanceSchedules,
    getMaintenanceSchedule,
    createMaintenanceSchedule,
    updateMaintenanceSchedule,
    deleteMaintenanceSchedule,
    activateMaintenanceSchedule,
    completeMaintenanceSchedule,
    getModules,
    getModule,
    updateModuleLevelAvailability,
    updateModulePaidPlan,
    updateModuleActive,
    getBatchLogs,
    getBetaRestrictionConfig,
    updateBetaRestrictionConfig,
  }
}
