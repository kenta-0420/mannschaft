/** route.query の複数値・0・負数・小数・安全整数外を受け付けない。 */
export function parseAnnouncementRouteId(value: unknown): number | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) throw new Error('Invalid content route identifier')
  const id = Number(value)
  if (!Number.isSafeInteger(id)) throw new Error('Invalid content route identifier')
  return id
}

export function blogAnnouncementScope(query: { teamId?: unknown; organizationId?: unknown }): { teamId?: number; organizationId?: number } {
  const teamId = parseAnnouncementRouteId(query.teamId)
  const organizationId = parseAnnouncementRouteId(query.organizationId)
  if (teamId !== undefined && organizationId !== undefined) throw new Error('Only one blog scope is allowed')
  return { teamId, organizationId }
}
