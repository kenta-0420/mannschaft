import type { GalleryAlbum, GalleryPhoto } from '~/types/gallery'

export function useGalleryApi() {
  const api = useApi()

  async function getAlbums(scopeType: string, scopeId: number) {
    return api<{ data: GalleryAlbum[] }>(`/api/v1/gallery/albums?scope_type=${scopeType}&scope_id=${scopeId}`)
  }

  async function getAlbum(albumId: number) {
    return api<{ data: GalleryAlbum & { photos: GalleryPhoto[] } }>(`/api/v1/gallery/albums/${albumId}`)
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
    return api<{ data: GalleryPhoto }>(`/api/v1/gallery/albums/${albumId}/photos`, { method: 'POST', body: formData })
  }

  async function deletePhoto(photoId: number) {
    return api(`/api/v1/gallery/photos/${photoId}`, { method: 'DELETE' })
  }

  async function downloadAlbum(albumId: number) {
    return api(`/api/v1/gallery/albums/${albumId}/download`)
  }

  return { getAlbums, getAlbum, createAlbum, updateAlbum, deleteAlbum, uploadPhoto, deletePhoto, downloadAlbum }
}
