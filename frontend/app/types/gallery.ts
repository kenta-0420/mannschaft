export type GalleryMediaType = 'PHOTO' | 'VIDEO'
export type GalleryProcessingStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'

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
  r2Key: string
  thumbnailR2Key: string | null
  url: string
  thumbnailUrl: string | null
  originalFileName: string
  contentType: string
  fileSize: number
  width: number | null
  height: number | null
  mediaType: GalleryMediaType
  durationSeconds: number | null
  videoCodec: string | null
  processingStatus: GalleryProcessingStatus
  caption: string | null
  uploadedBy: { id: number; displayName: string } | null
  createdAt: string
}
