export type CardType = 'NOTE' | 'IMAGE' | 'LINK' | 'CHECKLIST' | 'FILE'

/**
 * バックエンド `CorkboardResponse` (Java) に対応する TS 型。
 * F09.8 Phase A2: 旧フィールド (`title` / `description` / `cardCount` / `isArchived` / `sections`) は
 * バックエンド実体に存在しないため除去し、実フィールド (`name` / `backgroundStyle` / `editPolicy` /
 * `isDefault` / `version` / `ownerId`) に揃える。
 */
export interface CorkboardResponse {
  id: number
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  scopeId: number | null
  ownerId: number | null
  name: string
  backgroundStyle: string | null
  editPolicy: string | null
  isDefault: boolean
  version: number | null
  createdAt: string
  updatedAt: string | null
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
