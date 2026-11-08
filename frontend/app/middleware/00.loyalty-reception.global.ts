import { captureLoyaltyReceptionToken } from '~/utils/loyaltyReceptionToken'
export default defineNuxtRouteMiddleware((to) => {
  if (import.meta.server || to.path !== '/my/team-points/receive') return
  captureLoyaltyReceptionToken({ pathname: to.path, search: '', hash: to.hash }, window.history)
  if (to.hash) return navigateTo({ path: to.path, query: to.query }, { replace: true })
})
