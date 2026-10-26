export interface TeamPageResponse {
  id: number
  teamId: number
  organizationId: number
  title: string
  slug: string
  pageType: string
  year: number | null
  description: string | null
  coverImageS3Key: string | null
  visibility: string
  status: string
  allowSelfEdit: boolean
  sortOrder: number
  createdBy: number
  createdAt: string
  updatedAt: string
  sections: SectionResponse[]
  members: MemberProfileResponse[]
}

export interface CreateTeamPageRequest {
  teamId?: number
  organizationId?: number
  title?: string
  slug?: string
  pageType: string
  year?: number
  description?: string
  coverImageS3Key?: string
  visibility?: string
}

export interface UpdateTeamPageRequest {
  title?: string
  slug?: string
  description?: string
  coverImageS3Key?: string
  visibility?: string
  allowSelfEdit?: boolean
  sortOrder?: number
}

export interface SectionResponse {
  id: number
  teamPageId: number
  sectionType: string
  title: string | null
  content: string | null
  imageS3Key: string | null
  imageCaption: string | null
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface CreateSectionRequest {
  sectionType: string
  title?: string
  content?: string
  imageS3Key?: string
  imageCaption?: string
  sortOrder?: number
}

export interface UpdateSectionRequest {
  title?: string
  content?: string
  imageS3Key?: string
  imageCaption?: string
  sortOrder?: number
}

export interface MemberProfileResponse {
  id: number
  teamPageId: number
  userId: number
  displayName: string
  memberNumber: string | null
  photoS3Key: string | null
  bio: string | null
  position: string | null
  customFieldValues: string | null
  sortOrder: number
  isVisible: boolean
  createdAt: string
  updatedAt: string
}

export interface FieldResponse {
  id: number
  fieldName: string
  fieldType: string
  description: string | null
  options: string[] | null
  isRequired: boolean
  sortOrder: number
  isActive: boolean
}

export interface CreateFieldRequest {
  fieldName?: string
  fieldType: string
  description?: string
  options?: string[]
  isRequired?: boolean
  sortOrder?: number
}

export interface UpdateFieldRequest {
  fieldName?: string
  fieldType: string
  description?: string
  options?: string[]
  isRequired?: boolean
  sortOrder?: number
  isActive?: boolean
}
