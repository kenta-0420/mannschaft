import type {
  AttendanceRequirementRule,
  AttendanceRequirementRuleListResponse,
  CreateRequirementRuleRequest,
  UpdateRequirementRuleRequest,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useAttendanceRequirementApi() {
  const api = useApi()

  // GET /api/v1/organizations/{orgId}/attendance-requirements?academicYear=YYYY
  async function listOrganizationRules(
    orgId: number,
    academicYear: number,
  ): Promise<AttendanceRequirementRuleListResponse> {
    const res = await api<ApiResponse<AttendanceRequirementRuleListResponse>>(
      `/api/v1/organizations/${orgId}/attendance-requirements`,
      { params: { academicYear } },
    )
    return res.data
  }

  // POST /api/v1/organizations/{orgId}/attendance-requirements
  async function createOrganizationRule(
    orgId: number,
    req: CreateRequirementRuleRequest,
  ): Promise<AttendanceRequirementRule> {
    const res = await api<ApiResponse<AttendanceRequirementRule>>(
      `/api/v1/organizations/${orgId}/attendance-requirements`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  // GET /api/v1/teams/{teamId}/attendance-requirements?academicYear=YYYY
  async function listTeamRules(
    teamId: number,
    academicYear: number,
  ): Promise<AttendanceRequirementRuleListResponse> {
    const res = await api<ApiResponse<AttendanceRequirementRuleListResponse>>(
      `/api/v1/teams/${teamId}/attendance-requirements`,
      { params: { academicYear } },
    )
    return res.data
  }

  // POST /api/v1/teams/{teamId}/attendance-requirements
  async function createTeamRule(
    teamId: number,
    req: CreateRequirementRuleRequest,
  ): Promise<AttendanceRequirementRule> {
    const res = await api<ApiResponse<AttendanceRequirementRule>>(
      `/api/v1/teams/${teamId}/attendance-requirements`,
      { method: 'POST', body: req },
    )
    return res.data
  }

  // PATCH /api/v1/attendance-requirements/{ruleId}
  async function updateRule(
    ruleId: number,
    req: UpdateRequirementRuleRequest,
  ): Promise<AttendanceRequirementRule> {
    const res = await api<ApiResponse<AttendanceRequirementRule>>(
      `/api/v1/attendance-requirements/${ruleId}`,
      { method: 'PATCH', body: req },
    )
    return res.data
  }

  // DELETE /api/v1/attendance-requirements/{ruleId}
  async function deleteRule(ruleId: number): Promise<void> {
    await api<ApiResponse<void>>(
      `/api/v1/attendance-requirements/${ruleId}`,
      { method: 'DELETE' },
    )
  }

  return {
    listOrganizationRules,
    createOrganizationRule,
    listTeamRules,
    createTeamRule,
    updateRule,
    deleteRule,
  }
}
