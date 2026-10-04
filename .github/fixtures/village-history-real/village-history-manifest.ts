// captureAndVerifyOwned成功後だけ呼ぶ。APIContext/credentialsをmanifestへ渡さない。
import type { Actor, Owned } from './village-history-fixture-helper.ts'

const uuid = (value: string) => {
  if (!/^[0-9A-F]{32}$/.test(value)) throw new Error('DB_UUID_HEX_INVALID')
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`.toLowerCase()
}
type HistoryProof = {
  orderedIdsHex: string[][]
  memberships: { id: string; villageId: string; subjectId: number | string; role: string; leftAt: string | Date | null }[]
  leftVillageRequestId: string
  businessSnapshotSha256: string
}

export function assembleUiManifest(actors: [Actor, Actor, Actor], owned: Owned, actual: unknown) {
  const proof = actual as HistoryProof
  if (!proof || !Array.isArray(proof.orderedIdsHex) || proof.orderedIdsHex.length !== 3
      || proof.orderedIdsHex.map((ids) => ids.length).join(',') !== '21,1,0'
      || !Array.isArray(proof.memberships) || proof.memberships.length !== 12
      || !/^[0-9a-f]{64}$/.test(proof.businessSnapshotSha256)) throw new Error('DB_PROOF_SHAPE_INVALID')
  const x = owned.villages[5]
  const y = owned.villages.find((v) => v.owner.userId === actors[1].userId)
  if (!x || !y || x.owner.userId !== actors[2].userId) throw new Error('PERSONA_VILLAGE_BINDING_INVALID')
  const actorsSafe = actors.map((actor, index) => {
    if (!/^[A-Z][A-Z0-9_]{2,60}$/.test(actor.credentialEnvPrefix)) throw new Error('CREDENTIAL_ENV_PREFIX_INVALID')
    const village = index === 1 ? y : x
    const matches = proof.memberships.filter((m) => uuid(m.villageId) === village.id
      && Number(m.subjectId) === actor.userId && m.role === (index === 0 ? 'VILLAGER' : 'HEADMAN')
      && (index === 0 ? m.leftAt !== null : m.leftAt === null))
    if (matches.length !== 1) throw new Error('PERSONA_ACTUAL_ROLE_NOT_EXACT')
    const membership = matches[0]
    const expectedIds = proof.orderedIdsHex[index].map(uuid)
    const representativeRows = expectedIds.map((id) => {
      const row = owned.requests.find((r) => r.id === id && r.subjectId === actor.userId)
      if (!row || !['APPROVED', 'WITHDRAWN', 'REJECTED', 'PENDING'].includes(row.status))
        throw new Error('UI_ROW_NOT_OWNED')
      return { id: row.id, status: row.status, subjectType: row.subjectType,
        message: row.message, reviewComment: row.reviewComment }
    })
    return { userId: actor.userId, credentialEnvPrefix: actor.credentialEnvPrefix,
      expectedIds, representativeRows,
      roleEvidence: { villageId: village.id, membershipId: uuid(membership.id), role: membership.role,
        leftAt: membership.leftAt instanceof Date ? membership.leftAt.toISOString() : membership.leftAt },
      ...(index === 0 ? { leftVillageRequestId: proof.leftVillageRequestId } : {}) }
  })
  if (new Set(actorsSafe.flatMap((a) => a.expectedIds)).size !== 22
      || !actorsSafe[0].expectedIds.slice(0, 20).includes(proof.leftVillageRequestId))
    throw new Error('UI_EXPECTATION_SET_INVALID')
  return { approvedOwnedFreshFixture: true, actors: actorsSafe }
}
