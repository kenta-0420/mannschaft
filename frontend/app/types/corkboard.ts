export type CardType = 'NOTE' | 'IMAGE' | 'LINK' | 'CHECKLIST' | 'FILE'

export interface CorkboardResponse {
  id: number
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  scopeId: number | null
  title: string
  description: string | null
  backgroundColor: string | null
  isArchived: boolean
  cardCount: number
  sections: CorkboardSection[]
  createdAt: string
}

export interface CorkboardSection {
  id: number
  boardId: number
  title: string
  sortOrder: number
  color: string | null
}

export interface CorkboardCard {
  id: number
  boardId: number
  sectionId: number | null
  cardType: CardType
  title: string
  content: string | null
  color: string | null
  positionX: number
  positionY: number
  width: number
  height: number
  isPinned: boolean
  isArchived: boolean
  imageUrl: string | null
  linkUrl: string | null
  checklistItems: Array<{ text: string; checked: boolean }> | null
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}
