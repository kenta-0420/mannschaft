<script setup lang="ts">
/**
 * チーム詳細「基本情報」タブ（永続シェル配下の子ルート）。
 *
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * TeamDetailInfo の双方向 emit（mapEmbed / regionCodes）はシェルコンテキストの
 * ミューテータへ委譲し、親 team ref を更新する。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const {
  team,
  isAdmin,
  templateLabel,
  visibilityLabel,
  mutators,
} = useTeamShellContext()
</script>

<template>
  <div class="mt-4">
    <TeamDetailInfo
      v-if="team"
      :team-id="teamSlug"
      :name="team.basicInfo?.name ?? ''"
      :name-kana="team.basicInfo?.nameKana ?? null"
      :nickname1="team.basicInfo?.nickname1 ?? null"
      :nickname2="team.basicInfo?.nickname2 ?? null"
      :template="team.location?.template ?? ''"
      :template-label="templateLabel[team.location?.template ?? ''] ?? team.location?.template ?? ''"
      :prefecture="team.location?.prefecture ?? null"
      :city="team.location?.city ?? null"
      :prefecture-code="team.location?.prefectureCode ?? null"
      :city-code="team.location?.cityCode ?? null"
      :visibility="team.visibility?.visibility ?? ''"
      :visibility-label="visibilityLabel[team.visibility?.visibility ?? ''] ?? team.visibility?.visibility ?? ''"
      :member-count="team.metadata?.memberCount ?? 0"
      :team-friend-count="team.social?.teamFriendCount ?? 0"
      :supporter-count="team.social?.supporterCount ?? 0"
      :supporter-enabled="team.visibility?.supporterEnabled ?? false"
      :description="null"
      :is-admin="isAdmin"
      :map-embed-url="team.metadata?.mapEmbedUrl ?? null"
      :timezone="team.timezone ?? 'Asia/Tokyo'"
      @updated:map-embed-url="mutators.updateTeamMapEmbed"
      @updated:region-codes="mutators.updateTeamRegionCodes"
      @updated:timezone="mutators.updateTeamTimezone"
    />
  </div>
</template>
