<script setup lang="ts">
/**
 * F08.7 順位UI Wave2: 管理者向けスコア入力ページ。
 *
 * ルート `[id]`=組織 publicId / `[tId]`=tournamentId。
 * org 管理者（ADMIN/DEPUTY_ADMIN）のみアクセス可。非管理者には入力 UI を一切出さず
 * 「権限がありません」を提示する（最終防衛は Wave0 BE の @PreAuthorize）。
 *
 * グリッド（ScoreEntryGrid）で節ごとに home/away スコアを一括保存し、
 * CSV 取込（ScoreCsvImport）でも一括反映できる。保存後は StandingsRecalc 経路で
 * 順位が自動再計算されるため、保存成功時に順位表への導線を案内する。
 */
import type { TournamentResponse, TournamentDivision } from '~/types/tournament'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const notification = useNotification()
const orgId = String(route.params.slug)
const tId = Number(route.params.tId)

const { getTournament, getDivisions } = useTournamentApi()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgId)

const tournament = ref<TournamentResponse | null>(null)
const divisions = ref<TournamentDivision[]>([])

const loading = ref(true)
/** 'forbidden' = 権限なし（非管理者 or 不可視大会）、'error' = その他取得失敗 */
const loadError = ref<'forbidden' | 'error' | null>(null)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    await loadPermissions()
    // 非管理者は入力 UI を露出しない（FE ガード。最終防衛は BE）。
    if (!isAdminOrDeputy.value) {
      loadError.value = 'forbidden'
      return
    }
    const [tRes, divRes] = await Promise.all([
      getTournament(orgId, tId),
      getDivisions(orgId, tId),
    ])
    tournament.value = tRes.data
    divisions.value = divRes.data ?? []
  } catch (e) {
    const status = (e as { response?: { status?: number }; statusCode?: number })?.response?.status
      ?? (e as { statusCode?: number })?.statusCode
    loadError.value = status === 403 || status === 404 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

function onSaved() {
  notification.info(t('tournament.scoreEntry.standingsHint'))
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-3">
      <BackButton
        :to="`/organizations/${orgId}/tournaments/${tId}`"
        :label="$t('tournament.scoreEntry.back')"
      />
      <NuxtLink
        v-if="!loadError"
        :to="`/organizations/${orgId}/tournaments/${tId}/standings`"
        class="ml-auto flex items-center gap-1.5 rounded-lg border border-surface-300 px-3 py-1.5 text-sm text-surface-600 transition hover:border-primary-400 hover:text-primary"
      >
        <i class="pi pi-list" />
        {{ $t('tournament.standings.title') }}
      </NuxtLink>
    </div>

    <h1 class="mb-4 text-xl font-bold">
      {{ tournament?.content?.name ?? '' }}
      <span class="ml-1 text-base font-normal text-surface-500">
        {{ $t('tournament.scoreEntry.title') }}
      </span>
    </h1>

    <PageLoading v-if="loading" size="40px" />

    <DashboardEmptyState
      v-else-if="loadError === 'forbidden'"
      icon="pi pi-lock"
      :message="$t('tournament.scoreEntry.forbidden')"
    />

    <DashboardEmptyState
      v-else-if="loadError === 'error'"
      icon="pi pi-exclamation-triangle"
      :message="$t('tournament.scoreEntry.loadFailed')"
    />

    <template v-else>
      <!-- スコアキーパー指名管理（主催組織 ADMIN のみ。load() で isAdminOrDeputy を確認済み）。 -->
      <ScorekeeperManager
        class="mb-6"
        :org-id="orgId"
        :tournament-id="tId"
      />

      <ScoreEntryGrid
        :org-id="orgId"
        :tournament-id="tId"
        :divisions="divisions"
        :scoring="tournament?.scoring ?? null"
        @saved="onSaved"
      />
    </template>
  </div>
</template>
