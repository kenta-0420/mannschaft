<script setup lang="ts">
import type { SpotlightItem } from '~/composables/useSpotlightApi'

const authStore = useAuthStore()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const greeting = useGreeting()
const timedMessage = useTimedMessage()
const hasFamilyTeam = computed(() => teamStore.myTeams.some((team) => team.template === 'FAMILY'))
const hasAdminOrDeputyRole = computed(() =>
  teamStore.myTeams.some(
    (team) => team.role === 'ADMIN' || team.role === 'SYSTEM_ADMIN' || team.role === 'DEPUTY_ADMIN',
  ),
)

const showTeamCreateDialog = ref(false)
const showOrgCreateDialog = ref(false)
const showConfig = ref(false)
const collapsedKeys = ref<Set<string>>(new Set())
const { sortedWidgets, visibleWidgets, isVisible, toggleWidget, reorder, ready } =
  useDashboardWidgets('personal')

const spotlightApi = useSpotlightApi()
const spotlightItems = ref<SpotlightItem[]>([])
const spotlightPrimary = computed(() => spotlightItems.value[0])
const spotlightSecondary = computed(() => spotlightItems.value[1])

async function loadSpotlight() {
  spotlightItems.value = await spotlightApi.fetchContent('DASHBOARD_TILE', 2, {
    scopeType: 'PERSONAL',
  })
}

function toggleCollapse(key: string) {
  const next = new Set(collapsedKeys.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedKeys.value = next
}

function onTeamCreated() {
  teamStore.fetchMyTeams()
}
function onOrgCreated() {
  orgStore.fetchMyOrganizations()
}

onMounted(() => {
  void loadSpotlight()
  nextTick(() => {
    if (window.innerWidth < 768)
      collapsedKeys.value = new Set(visibleWidgets.value.map((widget) => widget.key))
  })
})
</script>

<template>
  <div>
    <div class="mb-6 flex items-start justify-between gap-4">
      <div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          {{ greeting }}、{{ authStore.currentUser?.fullName ?? 'ユーザー' }}さん
        </h1>
        <p class="mt-1 text-sm text-surface-500">{{ timedMessage }}</p>
      </div>
    </div>
    <div class="mb-2 flex justify-end">
      <Button
        :label="$t('dashboard.widget_settings.config_button')"
        icon="pi pi-cog"
        text
        size="small"
        @click="showConfig = true"
      />
    </div>

    <WidgetCommandCenter />
    <div v-if="hasFamilyTeam" class="mb-4"><WidgetFamilyHub /></div>

    <div v-if="!ready" class="mb-8 space-y-3" aria-busy="true">
      <Skeleton height="7rem" /><Skeleton height="3.5rem" /><Skeleton height="3.5rem" />
    </div>
    <template v-else>
      <DashboardPersonalAccordion
        :widgets="visibleWidgets"
        :collapsed-keys="collapsedKeys"
        class="mb-8"
        @toggle-collapse="toggleCollapse"
        @configure="showConfig = true"
      />
    </template>

    <div v-if="hasAdminOrDeputyRole" class="mb-4"><WidgetAdminBusinessAlert /></div>
    <WidgetSpotlightPrimary v-if="spotlightPrimary" class="mb-4" :item="spotlightPrimary" />
    <WidgetSpotlightSecondary v-if="spotlightSecondary" class="mb-4" :item="spotlightSecondary" />

    <div class="mt-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      <div
        class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-search text-primary" />
          <h2 class="text-lg font-semibold">{{ $t('dashboard.personal.find_team_title') }}</h2>
        </div>
        <p class="mb-4 text-sm text-surface-500">
          {{ $t('dashboard.personal.find_team_description') }}
        </p>
        <div class="flex flex-wrap gap-3">
          <Button
            :label="$t('dashboard.personal.find_team_button')"
            icon="pi pi-users"
            outlined
            @click="navigateTo('/teams')"
          /><Button
            :label="$t('dashboard.personal.find_org_button')"
            icon="pi pi-building"
            outlined
            @click="navigateTo('/organizations')"
          />
        </div>
      </div>
      <div
        class="rounded-xl border border-dashed border-surface-300 bg-surface-50 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="mb-3 flex items-center gap-2">
          <i class="pi pi-plus-circle text-primary" />
          <h2 class="text-lg font-semibold">{{ $t('dashboard.personal.create_team_title') }}</h2>
        </div>
        <p class="mb-4 text-sm text-surface-500">
          {{ $t('dashboard.personal.create_team_description') }}
        </p>
        <div class="flex flex-wrap gap-3">
          <Button
            :label="$t('dashboard.personal.create_team_button')"
            icon="pi pi-users"
            outlined
            @click="showTeamCreateDialog = true"
          /><Button
            :label="$t('dashboard.personal.create_org_button')"
            icon="pi pi-building"
            outlined
            @click="showOrgCreateDialog = true"
          />
        </div>
      </div>
    </div>

    <EntityCreateDialog
      entity-type="team"
      :visible="showTeamCreateDialog"
      @update:visible="showTeamCreateDialog = $event"
      @created="onTeamCreated"
    />
    <EntityCreateDialog
      entity-type="organization"
      :visible="showOrgCreateDialog"
      @update:visible="showOrgCreateDialog = $event"
      @created="onOrgCreated"
    />
    <DashboardConfigDialog
      v-model:visible="showConfig"
      :widgets="sortedWidgets"
      :is-visible="isVisible"
      @toggle="toggleWidget"
      @reorder="reorder"
    />
  </div>
</template>
