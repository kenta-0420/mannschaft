export interface WallpaperResponse {
  id: number
  templateSlug: string | null
  name: string
  imageUrl: string
  thumbnailUrl: string | null
  category: string | null
  sortOrder: number
  active: boolean
}

export function useWallpaperApi() {
  const api = useApi()

  async function listWallpapers(teamId: number) {
    return api<{ data: WallpaperResponse[] }>(`/api/v1/teams/${teamId}/wallpapers`)
  }

  return {
    listWallpapers,
  }
}
