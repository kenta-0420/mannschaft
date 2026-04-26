import type { WorkConstraint } from '~/types/shift'

export function useWorkConstraint(teamId: Ref<number>) {
  const shiftApi = useShiftApi()
  const constraints = ref<WorkConstraint[]>([])

  async function fetchConstraints(): Promise<void> {
    const res = await shiftApi.getWorkConstraints(teamId.value)
    constraints.value = (res as { data: WorkConstraint[] }).data
  }

  async function upsertDefault(request: Partial<WorkConstraint>): Promise<void> {
    await shiftApi.upsertDefaultConstraint(teamId.value, request as Record<string, unknown>)
    await fetchConstraints()
  }

  async function upsertMember(userId: number, request: Partial<WorkConstraint>): Promise<void> {
    await shiftApi.upsertMemberConstraint(
      teamId.value,
      userId,
      request as Record<string, unknown>,
    )
    await fetchConstraints()
  }

  async function deleteMember(userId: number): Promise<void> {
    await shiftApi.deleteMemberConstraint(teamId.value, userId)
    await fetchConstraints()
  }

  return { constraints, fetchConstraints, upsertDefault, upsertMember, deleteMember }
}
