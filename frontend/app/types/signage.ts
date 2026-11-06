export type SignageLayout = 'FULLSCREEN' | 'SPLIT_HORIZONTAL' | 'SPLIT_VERTICAL' | 'QUAD'
export type SignageTransition = 'NONE' | 'FADE' | 'SLIDE' | 'ZOOM'
export type SignageSlotType = 'IMAGE' | 'VIDEO' | 'URL' | 'ANNOUNCEMENT' | 'SCHEDULE' | 'WEATHER'

export interface SignageScreen {
  id: number
  scopeType: string
  scopeId: number
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
  label: string | null
  lastSeenAt: string | null
  createdAt: string
}

export interface CreateSignageScreenRequest {
  scopeType: string
  scopeId: number
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
