<script setup lang="ts">
// F09.19.4b 運用型キャンペーン（バナー広告）新規作成（チームスコープ）
import type { ScopeType } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const scopeType: ScopeType = 'TEAM'
</script>

<template>
  <OperationalCampaignFormView
    :scope-type="scopeType"
    :scope-id="teamSlug"
    :base-prefix="`/teams/${teamSlug}/advertiser`"
  />
</template>
