export type EquipmentStatus = 'AVAILABLE' | 'ASSIGNED' | 'MAINTENANCE' | 'RETIRED'
export type EquipmentType = 'REUSABLE' | 'CONSUMABLE'

export interface EquipmentResponse {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  name: string
  description: string | null
  category: string | null
  equipmentType: EquipmentType
  status: EquipmentStatus
  imageUrl: string | null
  quantity: number
  availableQuantity: number
  assignedTo: { userId: number; displayName: string } | null
  assignedAt: string | null
  returnDueDate: string | null
  qrCode: string | null
  createdAt: string
  updatedAt: string
}

export interface EquipmentHistory {
  id: number
  action: 'ASSIGNED' | 'RETURNED' | 'CONSUMED' | 'MAINTAINED'
  user: { id: number; displayName: string }
  quantity: number
  note: string | null
  createdAt: string
}
