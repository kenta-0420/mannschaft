<script setup lang="ts">
/**
 * F15.4 組織内チーム（店舗）検索 — 結果カード
 *
 * 設計書: docs/features/F15.4_team_store_search_within_org.md §5
 *          docs/features/F15.4_phase5_team_public_detail.md §6.3
 *
 * - `compact=true`（未ログイン / 非メンバー）: 抑制版表示。
 *   メンバー数を隠し、詳細遷移先は `/public/teams/{id}`（未ログイン公開ページ）。
 * - `compact=false`（既定 / ログイン済み）: 通常表示。詳細遷移先は `/teams/{id}`。
 *
 * Phase 5-γ: compact モードでも `/public/teams/{id}` への NuxtLink を有効化した。
 *   従来は遷移無効＋「ログインしてください」CTA のみだったが、Phase 5 で未ログイン
 *   詳細ページが公開されたため、コンバージョン経路として機能させる。
 */
import type { TeamSearchItem } from '~/types/team-search'

const props = withDefaults(
  defineProps<{
    team: TeamSearchItem
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)

const { templateLabel } = useScopeLabels()

const displayName = computed(() => props.team.name)
const templateText = computed(() => {
  const tpl = props.team.template
  if (!tpl) return null
  return templateLabel[tpl] ?? tpl
})

/**
 * 遷移先 URL。ログイン状態（compact=false）= 既存詳細ページ、未ログイン（compact=true）= 公開詳細ページ。
 */
const targetUrl = computed(() => {
  return props.compact ? `/public/teams/${props.team.slug}` : `/teams/${props.team.slug}`
})

function formatLocation(prefecture: string | null, city: string | null): string {
  return [prefecture, city].filter(Boolean).join(' ') || '-'
}
</script>

<template>
  <NuxtLink
    :to="targetUrl"
    class="block cursor-pointer rounded-lg border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md"
    :data-testid="compact ? 'team-search-card-public' : 'team-search-card-member'"
  >
    <div class="mb-3 flex items-center gap-3">
      <Avatar
        :image="team.iconUrl ?? undefined"
        :label="team.iconUrl ? undefined : displayName.charAt(0)"
        shape="circle"
        size="large"
      />
      <div class="min-w-0 flex-1">
        <h3 class="truncate font-semibold">
          {{ displayName }}
        </h3>
        <Tag
          v-if="templateText"
          :value="templateText"
          severity="info"
          class="text-xs"
        />
      </div>
    </div>

    <div class="flex items-center justify-between text-sm text-gray-500">
      <span>
        <i class="pi pi-map-marker mr-1" />{{ formatLocation(team.prefecture, team.city) }}
      </span>
    </div>

    <div
      v-if="compact"
      class="mt-3 border-t border-surface-100 pt-3 text-sm text-primary"
    >
      <i class="pi pi-sign-in mr-1" />{{ $t('organizationTeamSearch.loginToSeeDetails') }}
    </div>
  </NuxtLink>
</template>
