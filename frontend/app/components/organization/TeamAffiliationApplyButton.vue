<script setup lang="ts">
/**
 * F01.2.1 8-A — 「チームとして加盟を申請」ボタン（組織シェルのヘッダー・公開ページ共通）。
 *
 * 表示条件は BE の eligibility（`GET /api/v1/me/org-affiliation-eligibility`）の `canApply` に従う。
 * 受付 off・加盟操作権限なし・見えない組織は BE 側で区別されず false になる。FE で権限を推測しない。
 * 未ログインでは eligibility を呼ばず、ボタンも出さない。
 *
 * 設計書: docs/features/F01.2.1_org_team_groups.md §13（チーム側: 加盟申請の入口 ①②）
 */
const props = defineProps<{
  orgSlug: string
}>()

const { t } = useI18n()
const authStore = useAuthStore()
const { canApply } = useTeamAffiliationApi()

const eligible = ref(false)
const showDialog = ref(false)

async function loadEligibility() {
  eligible.value = false
  if (!authStore.isAuthenticated) return
  try {
    eligible.value = await canApply(props.orgSlug)
  }
  catch {
    // 判定できないときはボタンを出さない（fail-close）。付随の導線であり、ページ本体の表示は止めない。
    eligible.value = false
  }
}

watch(() => props.orgSlug, () => void loadEligibility(), { immediate: true })
</script>

<template>
  <span v-if="eligible" class="inline-flex">
    <Button
      :label="t('teamAffiliation.apply.button')"
      icon="pi pi-sitemap"
      severity="secondary"
      outlined
      size="small"
      :title="t('teamAffiliation.public_page_apply_hint')"
      data-testid="team-affiliation-apply-button"
      @click="showDialog = true"
    />
    <TeamAffiliationApplyDialog v-model:visible="showDialog" :org-slug="orgSlug" />
  </span>
</template>
