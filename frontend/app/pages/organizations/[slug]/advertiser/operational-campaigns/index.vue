<script setup lang="ts">
// F09.19.4b 運用型キャンペーン（バナー広告）一覧（組織スコープ）
import type { ScopeType } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const scopeType: ScopeType = 'ORGANIZATION'
</script>

<template>
  <OperationalCampaignListView
    :scope-type="scopeType"
    :scope-id="orgSlug"
    :base-prefix="`/organizations/${orgSlug}/advertiser`"
  />
</template>
