<script setup lang="ts">
// F09.19.4b 運用型キャンペーン（バナー広告）編集（組織スコープ）
import type { ScopeType } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const campaignId = String(route.params.campaignId)
const scopeType: ScopeType = 'ORGANIZATION'
</script>

<template>
  <OperationalCampaignFormView
    :scope-type="scopeType"
    :scope-id="orgSlug"
    :campaign-id="campaignId"
    :base-prefix="`/organizations/${orgSlug}/advertiser`"
  />
</template>
