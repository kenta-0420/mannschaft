import type {
  ClassHomeroomResponse,
  ClassHomeroomCreateRequest,
  ClassHomeroomUpdateRequest,
} from '~/types/school'

interface ApiResponse<T> {
  data: T
}

export function useClassHomeroomApi() {
  const api = useApi()

  async function getHomerooms(teamId: string, academicYear: number): Promise<ClassHomeroomResponse[]> {
    const res = await api<ApiResponse<ClassHomeroomResponse[]>>(
      `/api/v1/teams/${teamId}/homerooms?academicYear=${academicYear}`,
    )
    return res.data
  }

  async function createHomeroom(
    teamId: string,
    body: ClassHomeroomCreateRequest,
  ): Promise<ClassHomeroomResponse> {
    const res = await api<ApiResponse<ClassHomeroomResponse>>(
      `/api/v1/teams/${teamId}/homerooms`,
      { method: 'POST', body },
    )
    return res.data
  }

  async function updateHomeroom(
    teamId: string,
    id: number,
    body: ClassHomeroomUpdateRequest,
  ): Promise<ClassHomeroomResponse> {
    const res = await api<ApiResponse<ClassHomeroomResponse>>(
      `/api/v1/teams/${teamId}/homerooms/${id}`,
      { method: 'PATCH', body },
    )
    return res.data
  }

  return { getHomerooms, createHomeroom, updateHomeroom }
}
