<script setup lang="ts">
/**
 * F08.7 順位UI Wave B-3: 大会スコアキーパー指名管理 UI（主催組織 ADMIN 向け）。
 *
 * 主催組織 ADMIN が「当該大会のスコア入力を許可するユーザー」を指名・解除・一覧する。
 * 指名されたユーザーは単発入力に加え batch/import も操作可能になる（最終防衛は BE @PreAuthorize）。
 *
 * 認可: 本コンポーネントは org 管理者（ADMIN/DEPUTY_ADMIN）にのみ表示される前提（親が v-if で制御）。
 * BE は ScorekeeperResponse に displayName を返さないため、表示名は userId フォールバック。
 *
 * ユーザー選択: BE が会員検索 API を提供していないため、MVP は確実に動く userId 直接指定方式。
 * 入力値は parseUserIdInput で正の整数のみ許可し、不正値は i18n で案内する。
 * 成功/失敗（403/404/冪等）は症状を隠さず i18n で提示する。
 */
import type { ScorekeeperResponse } from '~/types/tournament'
import { extractStatus, isForbiddenError } from '~/utils/tournamentScoreEntry'
import { parseUserIdInput, isAlreadyScorekeeper } from '~/utils/tournamentScorekeeper'

const props = defineProps<{
  orgId: string
  tournamentId: number
}>()

const { t } = useI18n()
const notification = useNotification()
const { listScorekeepers, addScorekeeper, removeScorekeeper } = useTournamentScorekeepers()

const scorekeepers = ref<ScorekeeperResponse[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const adding = ref(false)
/** 解除処理中の指名 ID（ボタン二度押し防止 + スピナー表示）。 */
const removingId = ref<string | null>(null)

/** ユーザー ID 入力（直接指定方式）。 */
const userIdInput = ref<string>('')

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

async function onAdd() {
  const userId = parseUserIdInput(userIdInput.value)
  if (userId === null) {
    notification.warn(t('tournament.scorekeeper.error.invalidUserId'))
    return
  }
  if (isAlreadyScorekeeper(scorekeepers.value, userId)) {
    notification.info(t('tournament.scorekeeper.alreadyAssigned'))
    return
  }
  adding.value = true
  try {
    await addScorekeeper(props.orgId, props.tournamentId, userId)
    notification.success(t('tournament.scorekeeper.addSuccess'))
    userIdInput.value = ''
    await load()
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

    <!-- 追加フォーム（userId 直接指定） -->
    <form class="mb-4 flex flex-wrap items-end gap-2" @submit.prevent="onAdd">
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
        <span class="text-sm">
          {{ $t('tournament.scorekeeper.userDisplay', { userId: sk.userId }) }}
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
