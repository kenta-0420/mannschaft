// 駐車場API共通ユーティリティ

export function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
  return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
}
