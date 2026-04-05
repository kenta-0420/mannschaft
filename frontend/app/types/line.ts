export interface LineConfigResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  channelId: string
  channelName: string
  isActive: boolean
  linkedUserCount: number
  createdAt: string
}

export interface SnsConfigResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  platform: 'INSTAGRAM' | 'TWITTER' | 'FACEBOOK'
  accountName: string
  isActive: boolean
  lastSyncAt: string | null
}

export interface DirectMailResponse {
  id: number
  teamId: number
  title: string
  subject: string
  body: string
  status: 'DRAFT' | 'SCHEDULED' | 'SENDING' | 'SENT' | 'FAILED' | 'CANCELLED'
  recipientCount: number
  sentCount: number
  openCount: number
  clickCount: number
  bounceCount: number
  scheduledAt: string | null
  sentAt: string | null
  createdAt: string
}

export interface DirectMailTemplate {
  id: number
  teamId: number
  name: string
  subject: string
  body: string
}

export interface AffiliateConfig {
  id: number
  name: string
  adSlot: string
  adCode: string
  isActive: boolean
  placement: 'SIDEBAR' | 'FOOTER' | 'BANNER'
}

export interface SignageScreen {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  description: string | null
  orientation: 'LANDSCAPE' | 'PORTRAIT'
  isActive: boolean
  currentSlots: Array<{ id: number; contentType: string; content: Record<string, unknown>; duration: number; sortOrder: number }>
}
