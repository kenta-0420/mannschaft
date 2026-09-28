import type { FetchError } from 'ofetch'

/** 確認通知の管理画面を、BE と同じ ADMIN / SEND_NOTIFICATION 境界で保護する。 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server) return

  const slug = String(to.params.slug ?? '')
  if (!slug) return

  const scopeType = to.path.startsWith('/organizations/') ? 'organization' : 'team'
  const scopeTop = `/${scopeType === 'team' ? 'teams' : 'organizations'}/${slug}`
  const access = useRoleAccess(scopeType, slug)
  const result = await access.loadPermissions()

  if (!result.ok) {
    const status = (result.error as FetchError)?.response?.status
    if (status === 403 || status === 404) {
      throw createError({ statusCode: 404, statusMessage: 'Not Found', fatal: true })
    }
    throw createError({ statusCode: 503, statusMessage: 'Service Unavailable', fatal: true })
  }

  if (access.isAdmin.value || access.can('SEND_NOTIFICATION')) return

  const nuxtApp = useNuxtApp()
  const toast = nuxtApp.$toast as
    | { add: (opts: Record<string, unknown>) => void }
    | undefined
  if (toast) {
    toast.add({
      severity: 'error',
      summary: nuxtApp.$i18n.t('adminConsole.middleware.accessDeniedTitle'),
      detail: nuxtApp.$i18n.t('adminConsole.middleware.accessDeniedBody'),
      life: 5000,
    })
  }
  return navigateTo(scopeTop)
})
