export type ParkingSpaceStatus = 'AVAILABLE' | 'ASSIGNED' | 'RESERVED' | 'MAINTENANCE'

export interface ParkingSpaceResponse {
  id: number
  teamId: number
  spaceNumber: string
  area: string | null
  spaceType: 'STANDARD' | 'COMPACT' | 'LARGE' | 'EV_CHARGING' | 'MOTORCYCLE' | 'DISABLED'
  status: ParkingSpaceStatus
  assignedTo: { userId: number; displayName: string; vehiclePlate: string } | null
  monthlyFee: number | null
  createdAt: string
}

export interface VehicleResponse {
  id: number
  userId: number
  plateNumber: string
  vehicleType: string | null
  make: string | null
  model: string | null
  color: string | null
  isDefault: boolean
  createdAt: string
}

export interface ParkingListing {
  id: number
  spaceId: number
  spaceNumber: string
  availableFrom: string
  availableUntil: string | null
  monthlyFee: number
  status: 'ACTIVE' | 'CLOSED'
}
