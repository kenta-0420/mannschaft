import type { components } from './generated'

export type SignageLayout = NonNullable<
  components['schemas']['CreateSignageScreenRequest']['layout']
>
export type SignageTransition = NonNullable<
  components['schemas']['CreateSignageScreenRequest']['transitionEffect']
>
export type SignageSlotType = NonNullable<
  components['schemas']['AddSignageSlotRequest']['slotType']
>

export interface SignageScreen {
  id: number
  scopeType: string
  scopeId: string
  name: string
  description: string | null
  layout: SignageLayout
  defaultSlideDuration: number
  transitionEffect: SignageTransition
  isActive: boolean
  createdAt: string
}

export interface SignageSlot {
  id: number
  screenId: number
  slotType: SignageSlotType
  contentSourceId: string | null
  durationSeconds: number
  displayCondition: string | null
  order: number
}

export interface SignageToken {
  id: number
  screenId: number
  token: string
  name: string
  lastSeenAt: string | null
  createdAt: string
}

export interface CreateSignageScreenRequest {
  scopeType: string
  scopeId: string
  name: string
  description?: string
  layout: SignageLayout
  defaultSlideDuration: number
  transitionEffect: SignageTransition
}

export interface UpdateSignageScreenRequest {
  name?: string
  description?: string
  layout?: SignageLayout
  defaultSlideDuration?: number
  transitionEffect?: SignageTransition
  isActive?: boolean
}

export interface AddSignageSlotRequest {
  slotType: SignageSlotType
  contentSourceId?: string
  durationSeconds: number
  displayCondition?: string
}
