<script setup lang="ts">
/**
 * F08.7 順位UI ③: 大会スコアキーパー指名管理 UI（主催組織 ADMIN 向け）。
 *
 * 主催組織 ADMIN が「当該大会のスコア入力を許可するユーザー」を指名・解除・一覧する。
 * 指名されたユーザーは単発入力に加え batch/import も操作可能になる（最終防衛は BE @PreAuthorize）。
 *
 * 認可: 本コンポーネントは org 管理者（ADMIN/DEPUTY_ADMIN）にのみ表示される前提（親が v-if で制御）。
 *
 * 表示名: BE は ScorekeeperResponse に displayName（NameResolverService 解決）を同梱する。
 *   退会済み/不明ユーザーは BE 既定フォールバック名。FE は displayName ?? '#userId' で安全に表示する。
 *
 * メンバー選択: org 会員証一覧 API（member-cards?q=&status=ACTIVE）を候補ソースに氏名検索オートコンプリート。
 *   q 入力（debounce）→候補取得→既指名/重複を除外→クリックで userId 確定→add。
 *   会員証未発行/緊急指名のフォールバックとして userId 直接入力欄も併存させる。
 * 成功/失敗（403/404/冪等）は症状を隠さず i18n で提示する。
 */
import { useDebounceFn } from '@vueuse/core'
import type { ScorekeeperResponse } from '~/types/tournament'
import type { MemberCardListItem } from '~/types/member-card'
import { extractStatus, isForbiddenError } from '~/utils/tournamentScoreEntry'
import {
  parseUserIdInput,
  isAlreadyScorekeeper,
  filterMemberCandidates,
} from '~/utils/tournamentScorekeeper'

const props = defineProps<{
  orgId: string
  tournamentId: number
}>()

const { t } = useI18n()
const notification = useNotification()
const { listScorekeepers, addScorekeeper, removeScorekeeper } = useTournamentScorekeepers()
const { searchOrgMembers } = useMemberCardApi()

const scorekeepers = ref<ScorekeeperResponse[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const adding = ref(false)
/** 解除処理中の指名 ID（ボタン二度押し防止 + スピナー表示）。 */
const removingId = ref<string | null>(null)

/** 氏名検索（オートコンプリート）。 */
const searchKeyword = ref<string>('')
const searching = ref(false)
const candidates = ref<MemberCardListItem[]>([])
/** 候補リストを表示中か（フォーカス中かつ入力あり）。 */
const showCandidates = ref(false)

/** ユーザー ID 直接入力（フォールバック）。 */
const userIdInput = ref<string>('')

/** 既指名・userId 重複を除いた表示用候補。 */
const filteredCandidates = computed(() =>
  filterMemberCandidates(candidates.value, scorekeepers.value),
)

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await listScorekeepers(props.orgId, props.tournamentId)
    scorekeepers.value = res.data ?? []
  }
  catch (e) {
    loadFailed.value = true
    if (isForbiddenError(e)) {
      notification.error(t('tournament.scorekeeper.error.forbidden'))
    }
    else {
      notification.error(t('tournament.scorekeeper.error.loadFailed'))
    }
  }
  finally {
    loading.value = false
  }
}

/** 指名済みユーザーの表示名（displayName 優先・なければ #userId）。 */
function skLabel(sk: ScorekeeperResponse): string {
  return sk.displayName ?? `#${sk.userId}`
}

/** 候補の表示名（displayName 優先・なければ #userId）。 */
function candidateLabel(c: MemberCardListItem): string {
  return c.displayName ?? `#${c.userId}`
}

const runSearch = useDebounceFn(async () => {
  const kw = searchKeyword.value.trim()
  if (kw === '') {
    candidates.value = []
    searching.value = false
    return
  }
  searching.value = true
  try {
    candidates.value = await searchOrgMembers(props.orgId, { q: kw, status: 'ACTIVE' })
  }
  catch (e) {
    candidates.value = []
    if (isForbiddenError(e)) {
      notification.error(t('tournament.scorekeeper.error.forbidden'))
    }
    else {
      notification.error(t('tournament.scorekeeper.error.searchFailed'))
    }
  }
  finally {
    searching.value = false
  }
}, 300)

function onSearchInput() {
  showCandidates.value = true
  void runSearch()
}

/** 候補をクリックして指名する。 */
async function onSelectCandidate(c: MemberCardListItem) {
  showCandidates.value = false
  searchKeyword.value = ''
  candidates.value = []
  await doAdd(c.userId)
}

/** userId 直接入力（フォールバック）で指名する。 */
async function onAddByUserId() {
  const userId = parseUserIdInput(userIdInput.value)
  if (userId === null) {
    notification.warn(t('tournament.scorekeeper.error.invalidUserId'))
    return
  }
  const ok = await doAdd(userId)
  if (ok) userIdInput.value = ''
}

/** 指名の共通処理。重複は info で弾く。成功で true。 */
async function doAdd(userId: number): Promise<boolean> {
  if (isAlreadyScorekeeper(scorekeepers.value, userId)) {
    notification.info(t('tournament.scorekeeper.alreadyAssigned'))
    return false
  }
  adding.value = true
  try {
    await addScorekeeper(props.orgId, props.tournamentId, userId)
    notification.success(t('tournament.scorekeeper.addSuccess'))
    await load()
    return true
  }
  catch (e) {
    if (isForbiddenError(e)) {
      notification.error(t('tournament.scorekeeper.error.forbidden'))
    }
    else {
      const status = extractStatus(e)
      notification.error(
        t('tournament.scorekeeper.error.addFailed', { status: status ?? '' }),
      )
    }
    return false
  }
  finally {
    adding.value = false
  }
}

async function onRemove(sk: ScorekeeperResponse) {
  removingId.value = sk.id
  try {
    await removeScorekeeper(props.orgId, props.tournamentId, sk.id)
    notification.success(t('tournament.scorekeeper.removeSuccess'))
    await load()
  }
  catch (e) {
    if (isForbiddenError(e)) {
      // 404 は既に解除済み（他者操作）の可能性もあるため一覧を再取得して整合させる。
      notification.error(t('tournament.scorekeeper.error.forbidden'))
      await load()
    }
    else {
      notification.error(t('tournament.scorekeeper.error.removeFailed'))
    }
  }
  finally {
    removingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <section class="rounded-lg border border-surface-200 p-4">
    <div class="mb-1 flex items-center gap-2">
      <i class="pi pi-users text-primary" />
      <h2 class="text-base font-semibold">{{ $t('tournament.scorekeeper.title') }}</h2>
    </div>
    <p class="mb-3 text-sm text-surface-500">
      {{ $t('tournament.scorekeeper.description') }}
    </p>

    <!-- 追加: 氏名検索オートコンプリート（主経路） -->
    <div class="mb-3">
      <label for="scorekeeper-search" class="mb-1 block text-xs text-surface-500">
        {{ $t('tournament.scorekeeper.searchLabel') }}
      </label>
      <div class="relative">
        <input
          id="scorekeeper-search"
          v-model="searchKeyword"
          type="text"
          autocomplete="off"
          class="w-full rounded-lg border border-surface-300 px-3 py-1.5 text-sm focus:border-primary-400 focus:outline-none"
          :placeholder="$t('tournament.scorekeeper.searchPlaceholder')"
          :disabled="adding"
          @input="onSearchInput"
          @focus="showCandidates = true"
        >
        <!-- 候補リスト -->
        <ul
          v-if="showCandidates && searchKeyword.trim() !== ''"
          class="absolute z-10 mt-1 max-h-60 w-full overflow-auto rounded-lg border border-surface-200 bg-white shadow-lg"
        >
          <li v-if="searching" class="px-3 py-2 text-sm text-surface-400">
            <i class="pi pi-spinner pi-spin mr-1" />
            {{ $t('tournament.scorekeeper.searching') }}
          </li>
          <li
            v-else-if="filteredCandidates.length === 0"
            class="px-3 py-2 text-sm text-surface-400"
          >
            {{ $t('tournament.scorekeeper.noCandidates') }}
          </li>
          <li
            v-for="c in filteredCandidates"
            v-else
            :key="c.id"
            class="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 text-sm hover:bg-primary-50"
            @mousedown.prevent="onSelectCandidate(c)"
          >
            <span>{{ candidateLabel(c) }}</span>
            <span class="text-xs text-surface-400">#{{ c.userId }}</span>
          </li>
        </ul>
      </div>
    </div>

    <!-- 追加: userId 直接指定（フォールバック） -->
    <form class="mb-4 flex flex-wrap items-end gap-2" @submit.prevent="onAddByUserId">
      <div class="flex flex-col gap-1">
        <label for="scorekeeper-user-id" class="text-xs text-surface-500">
          {{ $t('tournament.scorekeeper.userIdLabel') }}
        </label>
        <input
          id="scorekeeper-user-id"
          v-model="userIdInput"
          type="text"
          inputmode="numeric"
          class="w-40 rounded-lg border border-surface-300 px-3 py-1.5 text-sm focus:border-primary-400 focus:outline-none"
          :placeholder="$t('tournament.scorekeeper.userIdPlaceholder')"
          :disabled="adding"
        >
      </div>
      <button
        type="submit"
        class="flex items-center gap-1.5 rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-white transition hover:bg-primary-600 disabled:opacity-50"
        :disabled="adding"
      >
        <i :class="adding ? 'pi pi-spinner pi-spin' : 'pi pi-plus'" />
        {{ $t('tournament.scorekeeper.add') }}
      </button>
    </form>

    <!-- 一覧 -->
    <PageLoading v-if="loading" size="28px" />

    <DashboardEmptyState
      v-else-if="loadFailed"
      icon="pi pi-exclamation-triangle"
      :message="$t('tournament.scorekeeper.error.loadFailed')"
    />

    <p v-else-if="scorekeepers.length === 0" class="text-sm text-surface-500">
      {{ $t('tournament.scorekeeper.empty') }}
    </p>

    <ul v-else class="divide-y divide-surface-100">
      <li
        v-for="sk in scorekeepers"
        :key="sk.id"
        class="flex items-center justify-between gap-3 py-2"
      >
        <span class="flex items-center gap-2 text-sm">
          <span class="font-medium">{{ skLabel(sk) }}</span>
          <span class="text-xs text-surface-400">#{{ sk.userId }}</span>
        </span>
        <button
          type="button"
          class="flex items-center gap-1 rounded-md border border-red-200 px-2 py-1 text-xs text-red-600 transition hover:bg-red-50 disabled:opacity-50"
          :disabled="removingId === sk.id"
          @click="onRemove(sk)"
        >
          <i :class="removingId === sk.id ? 'pi pi-spinner pi-spin' : 'pi pi-times'" />
          {{ $t('tournament.scorekeeper.remove') }}
        </button>
      </li>
    </ul>
  </section>
</template>
