export interface TeamCareOverrideResponse {
  id: number
  scopeType: string
  scopeId: string
  careLinkId: number
  notifyOnRsvp: boolean
  notifyOnCheckin: boolean
  notifyOnCheckout: boolean
  notifyOnAbsentAlert: boolean
  notifyOnDismissal: boolean
  disabled: boolean
}

export interface TeamCareOverrideRequest {
  notifyOnRsvp?: boolean
  notifyOnCheckin?: boolean
  notifyOnCheckout?: boolean
  notifyOnAbsentAlert?: boolean
  notifyOnDismissal?: boolean
  disabled?: boolean
}
