import type { VoteSessionResponse } from '~/types/voting'

export function useVotingApi() {
  const api = useApi()

  function qs(params: Record<string, unknown>): string {
    const q = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) q.set(k, String(v))
    }
    return q.toString()
  }

  // === Sessions ===
  async function getSessions(params?: Record<string, unknown>) {
    return api<{ data: VoteSessionResponse[] }>(`/api/v1/proxy-votes?${qs(params || {})}`)
  }
  async function getSession(id: number) {
    return api<{ data: VoteSessionResponse }>(`/api/v1/proxy-votes/${id}`)
  }
  async function createSession(body: Record<string, unknown>) {
    return api<{ data: VoteSessionResponse }>('/api/v1/proxy-votes', { method: 'POST', body })
  }
  async function updateSession(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/proxy-votes/${id}`, { method: 'PUT', body })
  }
  async function deleteSession(id: number) {
    return api(`/api/v1/proxy-votes/${id}`, { method: 'DELETE' })
  }
  async function openSession(id: number) {
    return api(`/api/v1/proxy-votes/${id}/open`, { method: 'PATCH' })
  }
  async function closeSession(id: number) {
    return api(`/api/v1/proxy-votes/${id}/close`, { method: 'PATCH' })
  }
  async function finalizeSession(id: number) {
    return api(`/api/v1/proxy-votes/${id}/finalize`, { method: 'PATCH' })
  }
  async function cloneSession(id: number) {
    return api(`/api/v1/proxy-votes/${id}/clone`, { method: 'POST' })
  }

  // === Motions ===
  async function addMotion(sessionId: number, body: Record<string, unknown>) {
    return api(`/api/v1/proxy-votes/${sessionId}/motions`, { method: 'POST', body })
  }
  async function updateMotion(motionId: number, body: Record<string, unknown>) {
    return api(`/api/v1/proxy-votes/motions/${motionId}`, { method: 'PUT', body })
  }
  async function deleteMotion(motionId: number) {
    return api(`/api/v1/proxy-votes/motions/${motionId}`, { method: 'DELETE' })
  }
  async function startVote(motionId: number) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/start-vote`, { method: 'PATCH' })
  }
  async function endVote(motionId: number) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/end-vote`, { method: 'PATCH' })
  }
  async function startAllVotes(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/start-all-votes`, { method: 'PATCH' })
  }

  // === Voting ===
  async function castVote(sessionId: number, votes: Array<{ motionId: number; choice: string }>) {
    return api(`/api/v1/proxy-votes/${sessionId}/cast`, { method: 'POST', body: { votes } })
  }
  async function changeVote(sessionId: number, votes: Array<{ motionId: number; choice: string }>) {
    return api(`/api/v1/proxy-votes/${sessionId}/cast`, { method: 'PUT', body: { votes } })
  }

  // === Delegation ===
  async function submitDelegation(sessionId: number, delegateId?: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/delegate`, {
      method: 'POST',
      body: { delegateId },
    })
  }
  async function cancelDelegation(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/delegate`, { method: 'DELETE' })
  }
  async function reviewDelegation(delegationId: number, status: 'ACCEPTED' | 'REJECTED') {
    return api(`/api/v1/proxy-votes/delegations/${delegationId}/review`, {
      method: 'PATCH',
      body: { status },
    })
  }

  // === Results ===
  async function getResults(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/results`)
  }
  async function getAttendance(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/attendance`)
  }
  async function exportResultsCsv(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/results/csv`)
  }
  async function getMyVotes() {
    return api('/api/v1/proxy-votes/my')
  }

  // === Comments ===
  async function getComments(motionId: number) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/comments`)
  }
  async function addComment(motionId: number, body: string) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/comments`, {
      method: 'POST',
      body: { body },
    })
  }
  async function deleteComment(motionId: number, commentId: number) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/comments/${commentId}`, {
      method: 'DELETE',
    })
  }

  // === Attachments ===
  async function addSessionAttachment(sessionId: number, body: FormData | Record<string, unknown>) {
    return api(`/api/v1/proxy-votes/${sessionId}/attachments`, { method: 'POST', body })
  }
  async function addMotionAttachment(motionId: number, body: FormData | Record<string, unknown>) {
    return api(`/api/v1/proxy-votes/motions/${motionId}/attachments`, { method: 'POST', body })
  }
  async function deleteAttachment(attachmentId: number) {
    return api(`/api/v1/proxy-votes/attachments/${attachmentId}`, { method: 'DELETE' })
  }

  // === Minutes ===
  async function getMinutesPdf(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/minutes-pdf`)
  }

  // === Remind ===
  async function sendReminder(sessionId: number) {
    return api(`/api/v1/proxy-votes/${sessionId}/remind`, { method: 'POST' })
  }

  return {
    getSessions,
    getSession,
    createSession,
    updateSession,
    deleteSession,
    openSession,
    closeSession,
    finalizeSession,
    cloneSession,
    addMotion,
    updateMotion,
    deleteMotion,
    startVote,
    endVote,
    startAllVotes,
    castVote,
    changeVote,
    submitDelegation,
    cancelDelegation,
    reviewDelegation,
    getResults,
    getAttendance,
    exportResultsCsv,
    getMyVotes,
    getComments,
    addComment,
    deleteComment,
    addSessionAttachment,
    addMotionAttachment,
    deleteAttachment,
    getMinutesPdf,
    sendReminder,
  }
}
