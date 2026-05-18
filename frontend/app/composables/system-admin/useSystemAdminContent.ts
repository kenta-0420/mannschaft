import type { PageMeta } from '~/types/api'
import type {
  AnnouncementResponse,
  CreateAnnouncementRequest,
  UpdateAnnouncementRequest,
  SystemTemplateResponse,
  CreateTemplateRequest,
  WallpaperResponse,
  CreateWallpaperRequest,
  ActivityTemplatePresetResponse,
} from '~/types/system-admin'

const BASE = '/api/v1/system-admin'

/**
 * システム管理者向けコンテンツ管理 API。
 * 取り扱う対象: お知らせ / テンプレート / テンプレート壁紙 / アクティビティテンプレートプリセット / ギャラリー。
 */
export function useSystemAdminContent() {
  const api = useApi()

  // ===== Announcements =====
  async function getAnnouncements(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: AnnouncementResponse[]; meta: PageMeta }>(`${BASE}/announcements?${query}`)
  }

  async function createAnnouncement(body: CreateAnnouncementRequest) {
    return api<{ data: AnnouncementResponse }>(`${BASE}/announcements`, { method: 'POST', body })
  }

  async function updateAnnouncement(id: number, body: UpdateAnnouncementRequest) {
    return api<{ data: AnnouncementResponse }>(`${BASE}/announcements/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteAnnouncement(id: number) {
    return api(`${BASE}/announcements/${id}`, { method: 'DELETE' })
  }

  async function publishAnnouncement(id: number) {
    return api(`${BASE}/announcements/${id}/publish`, { method: 'PATCH' })
  }

  // ===== Templates =====
  async function createTemplate(body: CreateTemplateRequest) {
    return api<{ data: SystemTemplateResponse }>(`${BASE}/templates`, { method: 'POST', body })
  }

  async function updateTemplate(id: number, body: Record<string, unknown>) {
    return api<{ data: SystemTemplateResponse }>(`${BASE}/templates/${id}`, {
      method: 'PATCH',
      body,
    })
  }

  async function deleteTemplate(id: number) {
    return api(`${BASE}/templates/${id}`, { method: 'DELETE' })
  }

  // ===== Template Wallpapers =====
  async function getTemplateWallpapers() {
    return api<{ data: WallpaperResponse[] }>(`${BASE}/template-wallpapers`)
  }

  async function createTemplateWallpaper(body: CreateWallpaperRequest) {
    return api<{ data: WallpaperResponse }>(`${BASE}/template-wallpapers`, { method: 'POST', body })
  }

  async function deleteTemplateWallpaper(id: number) {
    return api(`${BASE}/template-wallpapers/${id}`, { method: 'DELETE' })
  }

  // ===== Activity Template Presets =====
  async function getActivityTemplatePresets() {
    return api<{ data: ActivityTemplatePresetResponse[] }>(`${BASE}/activity-template-presets`)
  }

  async function createActivityTemplatePreset(body: Record<string, unknown>) {
    return api<{ data: ActivityTemplatePresetResponse }>(`${BASE}/activity-template-presets`, {
      method: 'POST',
      body,
    })
  }

  async function updateActivityTemplatePreset(id: number, body: Record<string, unknown>) {
    return api<{ data: ActivityTemplatePresetResponse }>(
      `${BASE}/activity-template-presets/${id}`,
      { method: 'PUT', body },
    )
  }

  async function deleteActivityTemplatePreset(id: number) {
    return api(`${BASE}/activity-template-presets/${id}`, { method: 'DELETE' })
  }

  // ===== Gallery =====
  async function regenerateThumbnails() {
    return api(`${BASE}/gallery/regenerate-thumbnails`, { method: 'POST' })
  }

  return {
    getAnnouncements,
    createAnnouncement,
    updateAnnouncement,
    deleteAnnouncement,
    publishAnnouncement,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    getTemplateWallpapers,
    createTemplateWallpaper,
    deleteTemplateWallpaper,
    getActivityTemplatePresets,
    createActivityTemplatePreset,
    updateActivityTemplatePreset,
    deleteActivityTemplatePreset,
    regenerateThumbnails,
  }
}
