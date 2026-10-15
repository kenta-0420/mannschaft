<script setup lang="ts">
import type { PublicStats } from '~/types/public-stats'

const { t } = useI18n()
const { getPublicStats } = usePublicStatsApi()

const stats = ref<PublicStats | null>(null)
const loading = ref(true)
const displayUsers = ref(0)
const displayTeams = ref(0)
const displayOrgs = ref(0)

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function animateCount(target: number, set: (v: number) => void) {
  if (prefersReducedMotion()) {
    set(target)
    return
  }
  const duration = 1400
  const start = Date.now()
  const tick = () => {
    const p = Math.min((Date.now() - start) / duration, 1)
    const eased = 1 - Math.pow(1 - p, 3)
    set(Math.round(eased * target))
    if (p < 1) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}

function fmt(n: number | null | undefined): string {
  if (n == null) return '-'
  if (n >= 10000) return `${Math.floor(n / 1000).toLocaleString()}K+`
  return n.toLocaleString()
}

onMounted(async () => {
  try {
    const data = await getPublicStats()
    stats.value = data
    animateCount(data.totalUsers, (v) => (displayUsers.value = v))
    animateCount(data.totalTeams, (v) => (displayTeams.value = v))
    animateCount(data.totalOrganizations, (v) => (displayOrgs.value = v))
  } catch {
    // API未実装・取得失敗時は '-' 表示（現行 LandingStats と同じ挙動）
  } finally {
    loading.value = false
  }
})

const cells = computed(() => [
  { icon: 'pi pi-user', label: t('landing.v2.stats.users'), value: stats.value ? displayUsers.value : null },
  { icon: 'pi pi-users', label: t('landing.v2.stats.teams'), value: stats.value ? displayTeams.value : null },
  { icon: 'pi pi-building', label: t('landing.v2.stats.organizations'), value: stats.value ? displayOrgs.value : null },
])
</script>

<template>
  <div class="mx-auto max-w-md" aria-live="polite">
    <p class="mb-2 text-center text-xs font-medium text-surface-400">
      <LpWrapText path="landing.v2.stats.heading_segments" />
    </p>
    <div class="grid grid-cols-3 gap-2 text-center">
      <div v-for="(c, i) in cells" :key="i" class="py-1">
        <div class="text-xl font-bold text-primary md:text-2xl">
          <span v-if="loading" class="text-surface-300">—</span>
          <template v-else>{{ fmt(c.value) }}</template>
        </div>
        <div class="mt-0.5 text-xs text-surface-500">
          <i :class="[c.icon, 'mr-1 text-[0.65rem]']" />{{ c.label }}
        </div>
      </div>
    </div>
  </div>
</template>
