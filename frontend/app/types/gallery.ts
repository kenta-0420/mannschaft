export interface GalleryAlbum {
  id: number
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
  title: string
  description: string | null
  coverPhotoUrl: string | null
  photoCount: number
  isPublic: boolean
  createdBy: { id: number; displayName: string } | null
  createdAt: string
  updatedAt: string
}

export interface GalleryPhoto {
  id: number
  albumId: number
  url: string
  thumbnailUrl: string
  originalFileName: string
  fileSize: number
  width: number
  height: number
  caption: string | null
  uploadedBy: { id: number; displayName: string } | null
  createdAt: string
}
