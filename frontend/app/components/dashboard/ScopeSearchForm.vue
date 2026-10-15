<script setup lang="ts">
/**
 * F22.1 スコープ検索フォーム（チーム / 組織）。
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/03_security_ux.md §2.8
 * - 文字入力 + submit のみ。フィルタ UI は持たない（要件 6）。
 * - submit で既存検索ページ（/teams/search・/organizations/search）へ keyword クエリ付きで遷移。
 * - キーワードのサニタイズ・長さ上限は遷移先の既存 search API が責任を持つ（§1.3）。
 *   navigateTo の query は自動エンコードされる。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'

const props = defineProps<{
  scopeType: ScopeTabType
}>()

const keyword = ref('')

const placeholder = computed(() =>
  props.scopeType === 'TEAM'
    ? 'scopeDashboard.searchTeam'
    : 'scopeDashboard.searchOrganization',
)

function onSubmit() {
  const path = props.scopeType === 'TEAM' ? '/teams/search' : '/organizations/search'
  const trimmed = keyword.value.trim()
  navigateTo({ path, query: trimmed ? { keyword: trimmed } : {} })
}
</script>

<template>
  <form
    class="flex items-center gap-2"
    :data-testid="`scope-search-form-${scopeType}`"
    @submit.prevent="onSubmit"
  >
    <IconField class="flex-1">
      <InputIcon class="pi pi-search" />
      <InputText
        v-model="keyword"
        :placeholder="$t(placeholder)"
        :aria-label="$t(placeholder)"
        class="field-bordered border-2 w-full"
        @keyup.enter="onSubmit"
      />
    </IconField>
    <Button
      type="submit"
      icon="pi pi-search"
      :label="$t(placeholder)"
      class="shrink-0"
    />
  </form>
</template>
