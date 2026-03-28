export type ResidentStatus = 'ACTIVE' | 'MOVED_OUT' | 'PENDING_VERIFICATION'

export interface DwellingUnit {
  id: number
  teamId: number
  unitNumber: string
  floor: number | null
  roomType: string | null
  area: number | null
  residents: ResidentResponse[]
  isVacant: boolean
  createdAt: string
}

export interface ResidentResponse {
  id: number
  dwellingUnitId: number
  userId: number | null
  displayName: string
  relationship: string | null
  status: ResidentStatus
  moveInDate: string
  moveOutDate: string | null
  leaseStartDate: string | null
  leaseEndDate: string | null
  isVerified: boolean
  createdAt: string
}

export interface PropertyListing {
  id: number
  teamId: number
  dwellingUnitId: number | null
  title: string
  description: string | null
  rent: number | null
  deposit: number | null
  availableFrom: string | null
  status: 'ACTIVE' | 'CLOSED' | 'DRAFT'
  imageUrls: string[]
  createdAt: string
}
