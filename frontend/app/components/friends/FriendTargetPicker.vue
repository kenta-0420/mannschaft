<script setup lang="ts">
import { useTeamCrud } from '~/composables/team/useTeamCrud'
import type { FriendRequestTarget } from '~/types/friends'
const props = defineProps<{ teamId: string; modelValue?: FriendRequestTarget | null; initialSlug?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: FriendRequestTarget | null] }>()
const { t } = useI18n()
const { searchTeams } = useTeamCrud()

/** 検索を始める最小文字数（trim 後） */
const SEARCH_MIN_LENGTH = 2
/** 候補の最大件数 */
const SEARCH_PAGE_SIZE = 10
/** 最後の入力から検索を始めるまでの待ち時間（ms） */
const SEARCH_DEBOUNCE_MS = 300

/** 候補として表示・選択に使う最小項目（検索 API のチーム要約から必要分だけ取る） */
interface TeamCandidate {
  name: string
  slug: string
}

type SearchStatus = 'idle' | 'loading' | 'done' | 'error'

/** AutoComplete の入力欄。入力中は string、候補を選ぶとチームオブジェクトになる。 */
const searchModel = ref<string | TeamCandidate | null>(null)
const suggestions = ref<TeamCandidate[]>([])
const searchStatus = ref<SearchStatus>('idle')
/** 直近に検索したキーワード（再検索に使う） */
const lastKeyword = ref('')
/** 古い検索結果が新しい結果を上書きしないための通し番号 */
let searchSeq = 0

const slugInput = ref('')

const autoCompleteRef = ref<{
  search: (event: Event | undefined, query: string, source: string) => void
  hide: () => void
} | null>(null)


const selectedTeam = computed<TeamCandidate | null>(() =>
  typeof searchModel.value === 'object' ? searchModel.value : null,
)

/** 送信対象の slug。直接入力があればそれを、なければ選択した候補の slug を使う。 */
const targetSlug = computed(() => slugInput.value.trim() || selectedTeam.value?.slug || '')

const isSelfFollow = computed(() => targetSlug.value !== '' && targetSlug.value === props.teamId)


/**
 * 候補が無いとき（0 件・検索失敗）は AutoComplete 標準のオーバーレイを閉じる。
 * 開いたままだと、パネルの外に出す自前の案内・再試行ボタンを覆い隠してしまう。
 * （suggestions が空のまま空で更新された場合、PrimeVue 側の自動クローズは動かない）
 */
async function closeOverlayWhenEmpty() {
  await nextTick()
  if (suggestions.value.length === 0) autoCompleteRef.value?.hide()
}

async function runSearch(keyword: string) {
  const seq = ++searchSeq
  lastKeyword.value = keyword
  searchStatus.value = 'loading'
  try {
    const result = await searchTeams({ keyword, page: 0, size: SEARCH_PAGE_SIZE })
    if (seq !== searchSeq) return
    suggestions.value = result.data.map(({ name, slug }) => ({ name, slug }))
    searchStatus.value = 'done'
    await closeOverlayWhenEmpty()
  }
  catch {
    // 失敗は 0 件と区別して表示し、再検索ボタンで復旧できるようにする（例外は握りつぶさない）
    if (seq !== searchSeq) return
    suggestions.value = []
    searchStatus.value = 'error'
    await closeOverlayWhenEmpty()
  }
}

/** AutoComplete の complete イベント（delay=300ms の debounce 後に発火する）。 */
function onComplete(event: { query: string }) {
  const keyword = event.query?.trim() ?? ''
  if (keyword.length < SEARCH_MIN_LENGTH) {
    searchSeq += 1
    suggestions.value = []
    searchStatus.value = 'idle'
    lastKeyword.value = ''
    return
  }
  void runSearch(keyword)
}

function retrySearch() {
  if (!lastKeyword.value) return
  // AutoComplete 経由で再検索すると、候補のオーバーレイも正しく開く
  autoCompleteRef.value?.search(undefined, lastKeyword.value, 'input')
}

function onCandidateSelect() {
  // 候補を選んだら直接入力は破棄する（送信先を 1 つに保つ）
  slugInput.value = ''
}

watch(searchModel, value => {
  if (typeof value === 'string') {
    searchSeq += 1
    suggestions.value = []
    searchStatus.value = 'idle'
  }
})

watch(slugInput, (next) => {
  // slug を直接入力し始めたら、選択済みの候補は破棄する
  if (next.trim() !== '' && selectedTeam.value) {
    searchModel.value = null
  }
})

function resetForm() {
  searchSeq += 1
  searchModel.value = null
  suggestions.value = []
  searchStatus.value = 'idle'
  lastKeyword.value = ''
  slugInput.value = ''
}

watch([targetSlug, selectedTeam], () => emit('update:modelValue', targetSlug.value ? { teamSlug: targetSlug.value, teamName: selectedTeam.value?.name ?? t('friends.request.entered_team') } : null))
watch(() => props.initialSlug, () => { resetForm(); slugInput.value = props.initialSlug ?? '' }, { immediate: true })
onBeforeUnmount(() => { searchSeq += 1 })
</script>
<template>
  <fieldset :disabled="disabled" class="flex min-w-0 flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium" for="friend-follow-search">
          {{ t('friends.follow_dialog.search_label') }}
        </label>
        <AutoComplete
          ref="autoCompleteRef"
          v-model="searchModel"
          :disabled="disabled"
          input-id="friend-follow-search"
          :suggestions="suggestions"
          option-label="name"
          :placeholder="t('friends.follow_dialog.search_placeholder')"
          :delay="SEARCH_DEBOUNCE_MS"
          :min-length="1"
          :show-empty-message="false"
          class="w-full"
          fluid
          data-testid="friend-follow-search-input"
          @complete="onComplete"
          @item-select="onCandidateSelect"
        >
          <template #option="{ option }">
            <div class="flex flex-col" data-testid="friend-follow-candidate">
              <span class="font-medium">{{ option.name }}</span>
              <span class="text-xs text-surface-500">{{ option.slug }}</span>
            </div>
          </template>
        </AutoComplete>
        <p class="mt-1 text-sm text-surface-500" data-testid="friend-follow-search-hint">
          {{ t('friends.follow_dialog.search_hint') }}
        </p>
        <div
          v-if="searchStatus === 'error'"
          class="mt-2 flex flex-wrap items-center gap-2 text-sm text-red-500"
          role="alert"
          data-testid="friend-follow-search-error"
        >
          <span>{{ t('friends.follow_dialog.search_error') }}</span>
          <Button
            :label="t('friends.follow_dialog.retry')"
            icon="pi pi-refresh"
            size="small"
            text
            class="whitespace-nowrap"
            data-testid="friend-follow-search-retry"
            @click="retrySearch"
          />
        </div>
        <p
          v-else-if="searchStatus === 'done' && suggestions.length === 0"
          class="mt-2 text-sm text-surface-500"
          data-testid="friend-follow-no-results"
        >
          {{ t('friends.follow_dialog.no_results') }}
          {{ t('friends.follow_dialog.slug_input_hint') }}
        </p>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium" for="friend-follow-slug">
          {{ t('friends.follow_dialog.slug_input_label') }}
        </label>
        <InputText
          id="friend-follow-slug"
          v-model="slugInput"
          :disabled="disabled"
          autocomplete="off"
          class="w-full"
          data-testid="friend-follow-slug-input"
        />
        <p
          v-if="isSelfFollow"
          class="mt-1 text-sm text-red-500"
          data-testid="friend-follow-self-warning"
        >
          {{ t('friends.errors.self_follow') }}
        </p>
      </div>

  </fieldset>
</template>
