import type { GalleryAlbum, GalleryPhoto } from '~/types/gallery'

export function useGalleryApi() {
  const api = useApi()

  async function getAlbums(scopeType: string, scopeId: number) {
    return api<{ data: GalleryAlbum[] }>(
      `/api/v1/gallery/albums?scope_type=${scopeType}&scope_id=${scopeId}`,
    )
  }

  async function getAlbum(albumId: number) {
    return api<{ data: GalleryAlbum & { photos: GalleryPhoto[] } }>(
      `/api/v1/gallery/albums/${albumId}`,
    )
  }

  async function createAlbum(body: Record<string, unknown>) {
    return api<{ data: GalleryAlbum }>('/api/v1/gallery/albums', { method: 'POST', body })
  }

  async function updateAlbum(albumId: number, body: Record<string, unknown>) {
    return api<{ data: GalleryAlbum }>(`/api/v1/gallery/albums/${albumId}`, { method: 'PUT', body })
  }

  async function deleteAlbum(albumId: number) {
    return api(`/api/v1/gallery/albums/${albumId}`, { method: 'DELETE' })
  }

  async function uploadPhoto(albumId: number, formData: FormData) {
    return api<{ data: GalleryPhoto }>(`/api/v1/gallery/albums/${albumId}/photos`, {
      method: 'POST',
      body: formData,
    })
  }

  async function updatePhoto(photoId: number, body: Record<string, unknown>) {
    return api<{ data: GalleryPhoto }>(`/api/v1/gallery/photos/${photoId}`, { method: 'PUT', body })
  }

  async function deletePhoto(photoId: number) {
    return api(`/api/v1/gallery/photos/${photoId}`, { method: 'DELETE' })
  }

  async function downloadAlbum(albumId: number) {
    return api(`/api/v1/gallery/albums/${albumId}/download`)
  }

  async function downloadPhoto(photoId: number) {
    return api(`/api/v1/gallery/photos/${photoId}/download`)
  }

  async function getPhotos(albumId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) q.set(k, String(v))
      }
    return api<{ data: GalleryPhoto[] }>(`/api/v1/gallery/albums/${albumId}/photos?${q}`)
  }

  return {
    getAlbums,
    getAlbum,
    createAlbum,
    updateAlbum,
    deleteAlbum,
    uploadPhoto,
    updatePhoto,
    deletePhoto,
    downloadAlbum,
    downloadPhoto,
    getPhotos,
  }
}
