<script setup lang="ts">
/**
 * F01.5 逆転送履歴一覧コンポーネント。
 *
 * 自チーム投稿が他フレンドチームへ転送された履歴を表示する。
 * 非公開フレンドの名前は「匿名チーム」に匿名化される。
 */
import type { FriendForwardExportView } from '~/types/friendForward'

const { t } = useI18n()
const { handleApiError } = useErrorHandler()
const { listForwardExports } = useFriendForwardApi()
const { formatRelative } = useRelativeTime()

const props = defineProps<{
  teamId: number
}>()

const exports = ref<FriendForwardExportView[]>([])
const loading = ref(true)
const currentPage = ref(0)
const pageSize = 20
const totalElements = ref(0)
const totalPages = ref(0)
const hasNext = ref(false)

/** データ取得 */
async function fetchExports() {
  loading.value = true
  try {
    const response = await listForwardExports(props.teamId, {
      page: currentPage.value,
      size: pageSize,
    })
    exports.value = response.data
    totalElements.value = response.pagination.totalElements
    totalPages.value = response.pagination.totalPages
    hasNext.value = response.pagination.hasNext
  }
  catch (error) {
    handleApiError(error, 'friend-forward-exports')
  }
  finally {
    loading.value = false
  }
}

/** ページ遷移 */
function goToPreviousPage() {
  if (currentPage.value > 0) {
    currentPage.value--
    fetchExports()
  }
}

function goToNextPage() {
  if (hasNext.value) {
    currentPage.value++
    fetchExports()
  }
}

/** 表示中の範囲計算 */
const showingFrom = computed(() => currentPage.value * pageSize + 1)
const showingTo = computed(() =>
  Math.min((currentPage.value + 1) * pageSize, totalElements.value),
)

/** チーム名の表示（匿名化対応） */
function displayTeamName(item: FriendForwardExportView): string {
  // 匿名チーム判定: バックエンドが匿名化時に返す固定文字列
  if (item.forwardingTeamName === '匿名チーム') {
    return t('forward_exports.list.anonymous_team')
  }
  return item.forwardingTeamName
}

/** 配信範囲の表示 */
function displayTarget(target: string): string {
  if (target === 'MEMBER') return t('forward_exports.list.target_member')
  if (target === 'MEMBER_AND_SUPPORTER') return t('forward_exports.list.target_supporter')
  return target
}

onMounted(() => {
  fetchExports()
})
</script>

<template>
  <div>
    <!-- ローディング -->
    <PageLoading v-if="loading" size="32px" />

    <!-- 空状態 -->
    <DashboardEmptyState
      v-else-if="exports.length === 0"
      icon="pi pi-history"
      :message="t('forward_exports.list.empty')"
    />

    <!-- 一覧テーブル -->
    <div v-else>
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-surface-200 dark:border-surface-600">
              <th class="px-3 py-2 text-left font-semibold">
                {{ t('forward_exports.columns.source_post') }}
              </th>
              <th class="px-3 py-2 text-left font-semibold">
                {{ t('forward_exports.columns.forwarding_team') }}
              </th>
              <th class="px-3 py-2 text-left font-semibold">
                {{ t('forward_exports.columns.target') }}
              </th>
              <th class="px-3 py-2 text-left font-semibold">
                {{ t('forward_exports.columns.forwarded_at') }}
              </th>
              <th class="px-3 py-2 text-left font-semibold">
                {{ t('forward_exports.columns.status') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in exports"
              :key="item.forwardId"
              class="border-b border-surface-100 dark:border-surface-700"
              :class="{ 'opacity-50': item.isRevoked }"
            >
              <td class="px-3 py-2">
                #{{ item.sourcePostId }}
              </td>
              <td class="px-3 py-2">
                {{ displayTeamName(item) }}
              </td>
              <td class="px-3 py-2">
                {{ displayTarget(item.target) }}
              </td>
              <td class="px-3 py-2 text-surface-500">
                {{ formatRelative(item.forwardedAt) }}
              </td>
              <td class="px-3 py-2">
                <Tag
                  v-if="item.isRevoked"
                  severity="warn"
                  :value="t('forward_exports.list.revoked')"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ページネーション -->
      <div
        v-if="totalPages > 1"
        class="mt-4 flex items-center justify-between text-sm text-surface-500"
      >
        <span>
          {{ t('forward_exports.pagination.showing', {
            from: showingFrom,
            to: showingTo,
            total: totalElements,
          }) }}
        </span>
        <div class="flex gap-2">
          <Button
            :label="t('forward_exports.pagination.prev')"
            icon="pi pi-chevron-left"
            size="small"
            text
            :disabled="currentPage === 0"
            @click="goToPreviousPage"
          />
          <Button
            :label="t('forward_exports.pagination.next')"
            icon="pi pi-chevron-right"
            icon-pos="right"
            size="small"
            text
            :disabled="!hasNext"
            @click="goToNextPage"
          />
        </div>
      </div>
    </div>
  </div>
</template>
