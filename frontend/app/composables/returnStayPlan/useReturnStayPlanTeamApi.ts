import type { TeamReturnStayPlan } from './useReturnStayPlanApi'
export interface MemberPlanItem { memberId: number; plans: TeamReturnStayPlan[] }
interface BatchResponse { data: { items: MemberPlanItem[] } }

export const RETURN_STAY_MEMBER_BATCH_SIZE = 400

export function chunkMemberIds(memberIds: number[], size = RETURN_STAY_MEMBER_BATCH_SIZE): number[][] {
  const chunks: number[][] = []
  for (let index = 0; index < memberIds.length; index += size) chunks.push(memberIds.slice(index, index + size))
  return chunks
}

export function mergeMemberPlans(memberIds: number[], batches: MemberPlanItem[]): MemberPlanItem[] {
  const merged = new Map(memberIds.map((memberId) => [memberId, [] as TeamReturnStayPlan[]]))
  batches.forEach((batch) => merged.set(batch.memberId, batch.plans))
  return memberIds.map((memberId) => ({ memberId, plans: merged.get(memberId) ?? [] }))
}

export function useReturnStayPlanTeamApi() {
  const api = useApi()
  const controller = shallowRef<AbortController | null>(null)
  let requestSequence = 0
  async function fetchForMembers(slug: string, memberIds: number[]) {
    controller.value?.abort()
    const current = new AbortController()
    const sequence = ++requestSequence
    controller.value = current
    try {
      const batches: MemberPlanItem[] = []
      for (const chunk of chunkMemberIds(memberIds)) {
        const query = chunk.map((id) => `memberIds=${encodeURIComponent(id)}`).join('&')
        const response = await api<BatchResponse>(`/api/v1/teams/${encodeURIComponent(slug)}/members/return-stay-plans?${query}`, { signal: current.signal })
        batches.push(...response.data.items)
      }
      return sequence === requestSequence ? mergeMemberPlans(memberIds, batches) : null
    } catch (error) {
      if (current.signal.aborted) return null
      throw error
    }
  }
  onBeforeUnmount(() => controller.value?.abort())
  return { fetchForMembers }
}
