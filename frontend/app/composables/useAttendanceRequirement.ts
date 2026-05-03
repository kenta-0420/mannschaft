import { ref } from 'vue'
import type {
  AttendanceRequirementRule,
  CreateRequirementRuleRequest,
  UpdateRequirementRuleRequest,
} from '~/types/school'

export function useAttendanceRequirement() {
  const rules = ref<AttendanceRequirementRule[]>([])
  const loading = ref(false)
  const submitting = ref(false)
  const { t } = useI18n()
  const { error: notifyError, success: notifySuccess } = useNotification()
  const api = useAttendanceRequirementApi()

  async function loadOrganizationRules(orgId: number, academicYear: number): Promise<void> {
    loading.value = true
    try {
      const res = await api.listOrganizationRules(orgId, academicYear)
      rules.value = res.rules
    } catch {
      notifyError(t('school.requirement.loadError'))
    } finally {
      loading.value = false
    }
  }

  async function loadTeamRules(teamId: number, academicYear: number): Promise<void> {
    loading.value = true
    try {
      const res = await api.listTeamRules(teamId, academicYear)
      rules.value = res.rules
    } catch {
      notifyError(t('school.requirement.loadError'))
    } finally {
      loading.value = false
    }
  }

  async function createRule(
    scope: { orgId?: number; teamId?: number },
    req: CreateRequirementRuleRequest,
    onSuccess?: () => void,
  ): Promise<void> {
    submitting.value = true
    try {
      let created: AttendanceRequirementRule | undefined
      if (scope.orgId) {
        created = await api.createOrganizationRule(scope.orgId, req)
      } else if (scope.teamId) {
        created = await api.createTeamRule(scope.teamId, req)
      }
      if (created) {
        rules.value.push(created)
        notifySuccess(t('school.requirement.createSuccess'))
        onSuccess?.()
      }
    } catch {
      notifyError(t('school.requirement.createError'))
    } finally {
      submitting.value = false
    }
  }

  async function updateRule(
    ruleId: number,
    req: UpdateRequirementRuleRequest,
    onSuccess?: () => void,
  ): Promise<void> {
    submitting.value = true
    try {
      const updated = await api.updateRule(ruleId, req)
      const idx = rules.value.findIndex(r => r.id === ruleId)
      if (idx >= 0) rules.value[idx] = updated
      notifySuccess(t('school.requirement.updateSuccess'))
      onSuccess?.()
    } catch {
      notifyError(t('school.requirement.updateError'))
    } finally {
      submitting.value = false
    }
  }

  async function deleteRule(ruleId: number): Promise<void> {
    submitting.value = true
    try {
      await api.deleteRule(ruleId)
      rules.value = rules.value.filter(r => r.id !== ruleId)
      notifySuccess(t('school.requirement.deleteSuccess'))
    } catch {
      notifyError(t('school.requirement.deleteError'))
    } finally {
      submitting.value = false
    }
  }

  return {
    rules,
    loading,
    submitting,
    loadOrganizationRules,
    loadTeamRules,
    createRule,
    updateRule,
    deleteRule,
  }
}
