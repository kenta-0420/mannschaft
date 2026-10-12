<script setup lang="ts">
// F01.2.1 §9.2 F1・F3: 試合画面の組織選択。チームの親組織が複数のとき、または指定が無効なときに表示する。
// 既定（syncQuery=true）は選択を URL クエリ `org` に載せる（再読込・共有しても変わらない）。
// 選択後の再読込は各ページが `route.query.org` の変化を watch して行う。
// URL を持たない場所（ウィジェット・予定パネル・個人分析）は syncQuery=false で
// `update:orgId` を受け取り、コンポーネントの状態に持つ。
// invalid=true（org が不正・親組織に無い）のときは警告を出し、選択肢から正しい組織へ戻れるようにする。
import type { MatchOrgOption } from '~/composables/match/useMatchOrgContext'

const props = withDefaults(
  defineProps<{
    /** チームの全親組織（代表親組織が先頭）。 */
    organizations: MatchOrgOption[]
    /** 現在使っている組織 ID（無効指定のときは null）。 */
    orgId: number | null
    /** 指定された組織が無効（作成・保存を止める状態）。 */
    invalid?: boolean
    /** 選択を URL クエリ org に載せる。false なら update:orgId を emit するだけ。 */
    syncQuery?: boolean
  }>(),
  { invalid: false, syncQuery: true },
)

const emit = defineEmits<{ 'update:orgId': [orgId: number] }>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

function optionLabel(o: MatchOrgOption): string {
  return o.name !== '' ? o.name : String(o.id)
}

async function onChange(event: Event): Promise<void> {
  const value = (event.target as HTMLSelectElement).value
  if (value === '') return
  if (props.syncQuery) {
    await router.replace({ query: { ...route.query, org: value } })
  }
  emit('update:orgId', Number(value))
}
</script>

<template>
  <div v-if="props.organizations.length > 1 || props.invalid" class="mb-4">
    <Message v-if="props.invalid" severity="warn" :closable="false" class="mb-2" data-testid="match-org-invalid">
      {{ t('match.org_select.invalid') }}
    </Message>
    <div v-if="props.organizations.length > 0" class="flex items-center gap-2 text-sm">
      <label for="match-org-select" class="text-surface-500">{{ t('match.org_select.label') }}</label>
      <select
        id="match-org-select"
        data-testid="match-org-select"
        class="rounded-lg border border-surface-300 bg-surface-0 px-2 py-1 dark:border-surface-600 dark:bg-surface-900"
        :value="props.orgId ?? ''"
        @change="onChange"
      >
        <option v-if="props.orgId === null" value="" disabled>{{ t('match.org_select.placeholder') }}</option>
        <option v-for="o in props.organizations" :key="o.id" :value="o.id">
          {{ optionLabel(o) }}
        </option>
      </select>
    </div>
  </div>
</template>
