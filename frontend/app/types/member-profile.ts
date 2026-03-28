export type PageType = 'MAIN' | 'YEARLY'
export type PageStatus = 'DRAFT' | 'PUBLISHED'
export type PageVisibility = 'PUBLIC' | 'MEMBERS_ONLY'
export type SectionType = 'TEXT' | 'IMAGE' | 'MEMBER_LIST' | 'HEADING'
export type FieldType = 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT'

export interface TeamPage {
  id: number
  pageType: PageType
  title: string
  year: number | null
  status: PageStatus
  visibility: PageVisibility
  sections: TeamPageSection[]
  createdAt: string
  updatedAt: string
}

export interface TeamPageSection {
  id: number
  sectionType: SectionType
  title: string | null
  content: string | null
  imageUrl: string | null
  sortOrder: number
}

export interface MemberProfile {
  id: number
  teamPageId: number
  userId: number | null
  displayName: string
  memberNumber: string | null
  photoUrl: string | null
  bio: string | null
  position: string | null
  customFields: Record<string, string>
  sortOrder: number
  isVisible: boolean
  createdAt: string
}

export interface MemberProfileField {
  id: number
  fieldName: string
  fieldType: FieldType
  options: string[] | null
  isRequired: boolean
  sortOrder: number
  isActive: boolean
}

export interface CreateMemberProfileRequest {
  displayName: string
  memberNumber?: string
  bio?: string
  position?: string
  customFields?: Record<string, string>
}
