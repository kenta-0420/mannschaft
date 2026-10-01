<script setup lang="ts">
// F01.2.1 §9.2 F1・F3: 試合画面の組織選択。チームの親組織が複数のときだけ表示する。
// 選択は URL クエリ `org` に載せる（再読込・共有しても変わらない）。
// 選択後の再読込は各ページが `route.query.org` の変化を watch して行う。
import type { MatchOrgOption } from '~/composables/match/useMatchOrgContext'

const props = defineProps<{
  /** チームの全親組織（代表親組織が先頭）。 */
  organizations: MatchOrgOption[]
  /** 現在使っている組織 ID。 */
  orgId: number | null
}>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

function optionLabel(o: MatchOrgOption): string {
  return o.name !== '' ? o.name : String(o.id)
}

async function onChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  await router.replace({ query: { ...route.query, org: value } })
}
</script>

<template>
  <div v-if="props.organizations.length > 1" class="mb-4 flex items-center gap-2 text-sm">
    <label for="match-org-select" class="text-surface-500">{{ t('match.org_select.label') }}</label>
    <select
      id="match-org-select"
      data-testid="match-org-select"
      class="rounded-lg border border-surface-300 bg-surface-0 px-2 py-1 dark:border-surface-600 dark:bg-surface-900"
      :value="props.orgId ?? ''"
      @change="onChange"
    >
      <option v-for="o in props.organizations" :key="o.id" :value="o.id">
        {{ optionLabel(o) }}
      </option>
    </select>
  </div>
</template>
