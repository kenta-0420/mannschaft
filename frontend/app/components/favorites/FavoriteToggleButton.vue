<script setup lang="ts">
// F02.9 Phase 3 — お気に入りトグルボタン。
// 仕様: 設計書 docs/features/F02.9_favorites_widget.md §5.4
//   - 任意エンティティ（TEAM / ORGANIZATION / KB_PAGE / BLOG_AUTHOR / VILLAGE）の
//     ページに設置し、☆ / ★ で登録状態を切り替える。
//   - 未認証時は非表示。
//   - マウント時に check API を呼んで初期状態を取得する。
//   - 上限 20 件超過時（FAV_002）は disabled 化し、ツールチップで通知する。
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '~/stores/useAuthStore'
import { useFavoritesApi } from '~/composables/useFavoritesApi'
import { useNotification } from '~/composables/useNotification'
import type { FavoriteEntityType } from '~/types/favorite'

const props = defineProps<{
  entityType: FavoriteEntityType
  entityId: string
  /** ARIA ラベル/ツールチップ表示用。未指定時は entityId にフォールバックする。 */
  entityName?: string
}>()

const emit = defineEmits<{
  toggled: [{ isFavorited: boolean }]
}>()

const { t } = useI18n()
const authStore = useAuthStore()
const { check, addFavorite, removeFavorite } = useFavoritesApi()
const notification = useNotification()

const isAuthenticated = computed<boolean>(() => authStore.isAuthenticated)
const isFavorited = ref(false)
const favoriteId = ref<string | null>(null)
const isLoading = ref(false)
const isToggling = ref(false)
const isLimitReached = ref(false)

const displayName = computed(() => props.entityName ?? props.entityId)
const ariaLabel = computed(() =>
  isFavorited.value
    ? t('favorites.toggleRemove', { name: displayName.value })
    : t('favorites.toggleAdd', { name: displayName.value }),
)
const tooltipText = computed(() =>
  isLimitReached.value && !isFavorited.value
    ? t('favorites.limitReached', { max: 20 })
    : '',
)

/** 起動時のお気に入り状態取得。失敗時はサイレントに OFF 扱い。 */
async function checkInitialState(): Promise<void> {
  if (!isAuthenticated.value) return
  isLoading.value = true
  try {
    const result = await check(props.entityType, props.entityId)
    isFavorited.value = result.isFavorited
    favoriteId.value = result.favoriteId
  } catch {
    // 初期チェック失敗は UI 上 OFF として扱い、トーストは出さない（過剰通知抑止）。
    isFavorited.value = false
    favoriteId.value = null
  } finally {
    isLoading.value = false
  }
}

/** エラーオブジェクトから ErrorCode（FAV_002 等）を抽出する。 */
function extractErrorCode(e: unknown): string | null {
  if (typeof e !== 'object' || e === null) return null
  const obj = e as Record<string, unknown>
  const data = obj.data as Record<string, unknown> | undefined
  const error = data?.error as Record<string, unknown> | undefined
  const code = error?.code
  return typeof code === 'string' ? code : null
}

async function handleToggle(): Promise<void> {
  if (!isAuthenticated.value || isToggling.value) return
  if (isLimitReached.value && !isFavorited.value) return

  isToggling.value = true
  try {
    if (isFavorited.value && favoriteId.value) {
      await removeFavorite(favoriteId.value)
      isFavorited.value = false
      favoriteId.value = null
      // 削除後は上限超過フラグも解除（1 件減ったため新規追加可能になる）。
      isLimitReached.value = false
      notification.success(t('favorites.removeSuccess'))
      emit('toggled', { isFavorited: false })
    } else {
      const item = await addFavorite(props.entityType, props.entityId)
      isFavorited.value = true
      favoriteId.value = item.favoriteId
      notification.success(t('favorites.addSuccess'))
      emit('toggled', { isFavorited: true })
    }
  } catch (e: unknown) {
    const errorCode = extractErrorCode(e)
    if (errorCode === 'FAV_002') {
      isLimitReached.value = true
      notification.error(t('favorites.limitReached', { max: 20 }))
    } else {
      notification.error(t('favorites.toggleError'))
    }
  } finally {
    isToggling.value = false
  }
}

onMounted(checkInitialState)
</script>

<template>
  <Button
    v-if="isAuthenticated"
    :icon="isLoading || isToggling ? 'pi pi-spinner pi-spin' : isFavorited ? 'pi pi-star-fill' : 'pi pi-star'"
    :label="isFavorited ? t('favorites.labelFavorited') : t('favorites.title')"
    :class="isFavorited ? 'border-yellow-400 bg-yellow-50 text-yellow-600' : ''"
    :disabled="isLoading || isToggling || (isLimitReached && !isFavorited)"
    :aria-pressed="isFavorited"
    :aria-label="ariaLabel"
    :title="tooltipText"
    severity="secondary"
    outlined
    size="small"
    :data-testid="`favorite-toggle-${entityType}-${entityId}`"
    @click="handleToggle"
  />
</template>
