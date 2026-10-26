<script setup lang="ts">
// F09.19.4b 運用型キャンペーン（バナー広告）一覧（チームスコープ）
// 組織版と同一コンポーネント（OperationalCampaignListView）を scope='TEAM' で流用。
import type { ScopeType } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const scopeType: ScopeType = 'TEAM'
</script>

<template>
  <OperationalCampaignListView
    :scope-type="scopeType"
    :scope-id="teamSlug"
    :base-prefix="`/teams/${teamSlug}/advertiser`"
  />
</template>
