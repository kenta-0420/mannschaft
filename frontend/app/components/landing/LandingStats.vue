<script setup lang="ts">
import type { PublicStats } from '~/types/public-stats'

const { t } = useI18n()
const { getPublicStats } = usePublicStatsApi()

const stats = ref<PublicStats | null>(null)
const statsLoading = ref(true)
const displayUsers = ref(0)
const displayTeams = ref(0)
const displayOrgs = ref(0)

function animateCount(target: number, set: (v: number) => void) {
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
    // API未実装時はnull表示（'-'）
  } finally {
    statsLoading.value = false
  }
})
</script>

<template>
  <section
    id="stats"
    aria-labelledby="stats-heading"
    class="border-y border-surface-200 bg-white py-12 dark:border-surface-700 dark:bg-surface-800"
  >
    <div class="mx-auto max-w-4xl px-4">
      <p id="stats-heading" class="mb-6 text-center text-sm font-medium text-surface-500">
        {{ t('landing.stats.heading') }}
      </p>
      <div class="grid grid-cols-3 gap-4 text-center" aria-live="polite">
        <div>
          <div class="text-3xl font-black text-primary md:text-4xl">
            <span v-if="statsLoading" class="text-surface-300">—</span>
            <template v-else>{{ fmt(stats ? displayUsers : null) }}</template>
          </div>
          <div class="mt-1.5 text-sm text-surface-500">
            <i class="pi pi-user mr-1 text-xs" />{{ t('landing.stats.users') }}
          </div>
        </div>
        <div class="border-x border-surface-200 dark:border-surface-700">
          <div class="text-3xl font-black text-primary md:text-4xl">
            <span v-if="statsLoading" class="text-surface-300">—</span>
            <template v-else>{{ fmt(stats ? displayTeams : null) }}</template>
          </div>
          <div class="mt-1.5 text-sm text-surface-500">
            <i class="pi pi-users mr-1 text-xs" />{{ t('landing.stats.teams') }}
          </div>
        </div>
        <div>
          <div class="text-3xl font-black text-primary md:text-4xl">
            <span v-if="statsLoading" class="text-surface-300">—</span>
            <template v-else>{{ fmt(stats ? displayOrgs : null) }}</template>
          </div>
          <div class="mt-1.5 text-sm text-surface-500">
            <i class="pi pi-building mr-1 text-xs" />{{ t('landing.stats.organizations') }}
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
