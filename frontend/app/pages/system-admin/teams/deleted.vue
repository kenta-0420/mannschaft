<script setup lang="ts">
import type { DeletedTeamSummary } from '~/composables/system-admin/useSystemAdminDashboard'

definePageMeta({ middleware: ['auth', 'system-admin'] })

const { t, locale } = useI18n()
const auth = useAuthStore()
const api = useSystemAdminApi()
const teamApi = useTeamApi()
const confirm = useConfirm()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { page, rows, totalRecords, onPage } = usePagination()
const teams = ref<DeletedTeamSummary[]>([])
const loading = ref(false)
const loadError = shallowRef<unknown>()
const restoringSlug = ref<string | null>(null)
const restoredTeam = ref<DeletedTeamSummary | null>(null)
useHead({ title: () => t('deletedTeams.title') })

async function load() {
  if (!auth.isSystemAdmin) return
  loading.value = true
  loadError.value = undefined
  try {
    let response = await api.getDeletedTeams({ page: page.value, size: rows.value })
    // 最終頁の最後の行が復元された場合、存在する最後の頁へ戻して取得する。
    const lastPage = Math.max(0, response.meta.totalPages - 1)
    if (page.value > lastPage) {
      page.value = lastPage
      response = await api.getDeletedTeams({ page: page.value, size: rows.value })
    }
    teams.value = response.data
    totalRecords.value = response.meta.total
  } catch (error) {
    teams.value = []
    loadError.value = error
    handleApiError(error, t('deletedTeams.title'))
  } finally {
    loading.value = false
  }
}

function changePage(event: { page: number; rows: number }) {
  if (restoringSlug.value || loading.value) return
  onPage(event)
  void load()
}

async function restore(selected: DeletedTeamSummary) {
  if (restoringSlug.value || !auth.isSystemAdmin) return
  restoringSlug.value = selected.slug
  try {
    try {
      await teamApi.restoreTeam(selected.slug)
    } catch (error) {
      handleApiError(error, t('deletedTeams.restore'))
      const apiError = error as {
        statusCode?: number
        status?: number
        response?: { status?: number }
      }
      const status = apiError.statusCode ?? apiError.status ?? apiError.response?.status
      if (status === 409 || status === 404) await load()
      return
    }
    restoredTeam.value = selected
    notification.success(t('deletedTeams.success', { name: selected.name }))
    // 復元204とその後のGET失敗は別結果。GETの失敗を復元失敗へ読み替えない。
    await load()
  } finally {
    restoringSlug.value = null
  }
}

function requestRestore(team: DeletedTeamSummary) {
  if (restoringSlug.value || loading.value || !auth.isSystemAdmin) return
  const selected = { ...team }
  confirm.require({
    group: 'deleted-team-restore',
    header: t('deletedTeams.confirmTitle'),
    message: t('deletedTeams.confirm', { name: selected.name, slug: selected.slug }),
    icon: 'pi pi-history',
    rejectLabel: t('deletedTeams.cancel'),
    acceptLabel: t('deletedTeams.restore'),
    rejectProps: { severity: 'secondary', outlined: true, class: 'min-h-11 min-w-11' },
    acceptProps: { class: 'min-h-11 min-w-11' },
    accept: () => {
      void restore(selected)
    },
  })
}

function formatDeletedAt(value: string): string {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value),
  )
}

onMounted(load)
</script>

<template>
  <div v-if="auth.isSystemAdmin" class="mx-auto w-full min-w-0 max-w-screen-lg space-y-4">
    <PageHeader :title="t('deletedTeams.title')" back-to="/system-admin" />
    <p class="text-surface-600 dark:text-surface-300">{{ t('deletedTeams.description') }}</p>
    <Message v-if="restoredTeam" severity="success" :closable="false" data-testid="restore-success">
      <p>{{ t('deletedTeams.success', { name: restoredTeam.name }) }}</p>
      <NuxtLink
        v-if="restoredTeam.lifecycleStatus === 'ACTIVE'"
        :to="`/teams/${restoredTeam.slug}`"
        class="inline-flex min-h-11 items-center underline"
        data-testid="restored-team-link"
        >{{ t('deletedTeams.openTeam') }}</NuxtLink
      >
      <p v-else>{{ t('deletedTeams.provisionedHint') }}</p>
    </Message>
    <p v-if="restoringSlug" role="status">{{ t('deletedTeams.processing') }}</p>
    <SectionCard>
      <PageLoading v-if="loading" />
      <DashboardErrorState
        v-else-if="loadError"
        :error="loadError"
        :show-retry="true"
        testid="deleted-teams-error"
        @retry="load"
      />
      <DashboardEmptyState v-else-if="teams.length === 0" :message="t('deletedTeams.empty')" />
      <template v-else>
        <ul class="divide-y divide-surface-200 dark:divide-surface-700">
          <li
            v-for="team in teams"
            :key="team.slug"
            class="flex min-w-0 flex-wrap items-center gap-3 py-4"
          >
            <div class="min-w-0 flex-1 break-words">
              <p class="font-semibold">{{ team.name }}</p>
              <p class="break-all text-sm text-surface-500">{{ team.slug }}</p>
              <p class="text-sm">
                {{ t('deletedTeams.deletedAt') }}: {{ formatDeletedAt(team.deletedAt) }}
              </p>
              <p class="text-sm">
                {{
                  t(
                    team.lifecycleStatus === 'ACTIVE'
                      ? 'deletedTeams.active'
                      : 'deletedTeams.provisioned',
                  )
                }}
              </p>
            </div>
            <Button
              :label="t('deletedTeams.restore')"
              icon="pi pi-history"
              class="min-h-11 min-w-11"
              :disabled="restoringSlug !== null"
              :loading="restoringSlug === team.slug"
              :data-testid="`restore-${team.slug}`"
              @click="requestRestore(team)"
            />
          </li>
        </ul>
        <fieldset :disabled="restoringSlug !== null" class="min-w-0">
          <Paginator
            :first="page * rows"
            :rows="rows"
            :total-records="totalRecords"
            :page-link-size="3"
            template="PrevPageLink PageLinks NextPageLink"
            :pt="{
              prev: { class: 'min-h-11 min-w-11' },
              page: { class: 'min-h-11 min-w-11' },
              next: { class: 'min-h-11 min-w-11' },
            }"
            @page="changePage"
          />
        </fieldset>
      </template>
    </SectionCard>
    <ConfirmDialog
      group="deleted-team-restore"
      :pt="{ pcCloseButton: { root: { class: 'min-h-11 min-w-11' } } }"
    />
  </div>
</template>
