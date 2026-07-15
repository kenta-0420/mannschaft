<script setup lang="ts">
/**
 * F17.1 村機能 — 個人ダッシュボード 井戸端ダイジェストウィジェット (VILLAGE_LOBBY_DIGEST)
 *
 * 設計書: docs/features/F17.1_village_community.md §3.12.5
 *
 * 機能:
 *   - ピン留め村の横断フィード API から村一覧を取得し、
 *     各村の本日の井戸端在席数をバッジ表示する
 *   - 在席数が 0 より大きい場合のみバッジを表示
 *   - 井戸端タブ (/villages/{villageId}/lobby) へのリンク
 *
 * データソース:
 *   - GET /api/v1/me/village-feed → ピン留め村一覧 (pinnedVillages)
 *   - GET /api/v1/villages/{villageId}/lobby/presence → 各村の在席状況
 *
 * 状態:
 *   - loading（スケルトン 2 件）
 *   - empty（ピン留め村なし → 誘導リンク）
 *   - 正常（ピン村ごとに在席バッジを表示）
 */
import type { LobbyPresenceResponse, VillagePinnedSummaryResponse } from '~/types/village'

const { t } = useI18n()
const villageApi = useVillageApi()
const { captureQuiet } = useErrorReport()

// =============================================================================
// 状態管理
// =============================================================================

const loading = ref(true)
const pinnedVillages = ref<VillagePinnedSummaryResponse[]>([])

/** 村ID → 在席数マップ */
const presenceMap = ref<Map<string, number>>(new Map())

const isEmpty = computed(() => pinnedVillages.value.length === 0)

/** 表示件数を最大 5 件に絞る */
const displayedVillages = computed(() => pinnedVillages.value.slice(0, 5))

// =============================================================================
// API 呼び出し
// =============================================================================

async function load() {
  loading.value = true
  presenceMap.value = new Map()
  try {
    const feedRes = await villageApi.getFeed()
    pinnedVillages.value = feedRes.pinnedVillages ?? []

    // ピン村ごとに在席状況を並列取得（失敗は静かに吸収）
    const presenceResults = await Promise.allSettled(
      pinnedVillages.value.slice(0, 5).map(async (v) => {
        const res: LobbyPresenceResponse = await villageApi.getLobbyPresence(v.id)
        return { id: v.id, count: res.activeCount }
      }),
    )
    const nextMap = new Map<string, number>()
    for (const result of presenceResults) {
      if (result.status === 'fulfilled') {
        nextMap.set(result.value.id, result.value.count)
      }
    }
    presenceMap.value = nextMap
  }
  catch (err) {
    captureQuiet(err, { context: 'WidgetVillageLobbyDigest: ピン村フィード取得失敗' })
    pinnedVillages.value = []
  }
  finally {
    loading.value = false
  }
}

function presenceCount(villageId: string): number {
  return presenceMap.value.get(villageId) ?? 0
}

onMounted(load)
</script>

<template>
  <div class="space-y-2">
    <!-- ローディング: スケルトン 2 件 -->
    <div v-if="loading" class="space-y-2">
      <Skeleton height="2.5rem" />
      <Skeleton height="2.5rem" />
    </div>

    <!-- 空: ピン留め村なし -->
    <div
      v-else-if="isEmpty"
      class="flex flex-col items-center gap-2 py-6 text-center"
    >
      <i class="pi pi-home text-2xl text-surface-300" />
      <p class="text-xs text-surface-500">
        {{ t('village.widget.lobbyDigest.empty') }}
      </p>
      <NuxtLink
        to="/villages"
        class="text-xs text-primary hover:underline"
      >
        {{ t('village.action.viewAll') }}
      </NuxtLink>
    </div>

    <!-- 正常: ピン村リスト + 在席バッジ -->
    <ul v-else class="space-y-1">
      <li
        v-for="village in displayedVillages"
        :key="village.id"
        class="flex items-center justify-between gap-2"
      >
        <NuxtLink
          :to="`/villages/${village.id}/lobby`"
          class="flex min-w-0 flex-1 items-center gap-2 rounded-lg px-2 py-1.5 text-sm text-surface-700 transition-colors hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
          :aria-label="village.name"
        >
          <i class="pi pi-comments shrink-0 text-sm text-primary" />
          <span class="truncate font-medium">{{ village.name }}</span>
        </NuxtLink>

        <!-- 在席数バッジ（0 より大きい場合のみ表示） -->
        <span
          v-if="presenceCount(village.id) > 0"
          class="inline-flex shrink-0 items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs font-semibold text-green-800 dark:bg-green-900/40 dark:text-green-300"
          :aria-label="t('village.widget.lobbyDigest.presence', { count: presenceCount(village.id) })"
        >
          <span
            class="inline-block h-1.5 w-1.5 rounded-full bg-green-500"
            aria-hidden="true"
          />
          {{ t('village.widget.lobbyDigest.presence', { count: presenceCount(village.id) }) }}
        </span>
      </li>
    </ul>
  </div>
</template>
