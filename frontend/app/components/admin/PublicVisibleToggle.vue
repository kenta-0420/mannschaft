<script setup lang="ts">
/**
 * F19.1 Phase 2: 個別投稿の public_visible トグルスイッチ。
 *
 * ADMIN 向けに各投稿の公開・非公開をトグルする UI コンポーネント。
 * 現在は UI のみ提供し、API 呼び出しは TODO として残している。
 *
 * TODO: バックエンド API（PATCH /api/v1/admin/posts/{postId}/public-visible）が
 *   Phase 3 以降で実装された後、usePublicApi composable から呼び出すこと。
 *   実装時は PublicVisiblePatchRequest 型（types/public.ts）を使用する。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §10
 */
// TODO: Phase 3 以降で API 実装時にインポートを有効化する
// import type { PublicVisiblePatchRequest } from '~/types/public'

const props = defineProps<{
  /** 対象投稿の ID */
  postId: number
  /** 現在の public_visible 状態 */
  publicVisible: boolean
  /** スコープ種別（チーム / 組織） */
  entityType: 'team' | 'organization'
}>()

const emit = defineEmits<{
  /** API 呼び出し成功後にトグル結果を親に通知する */
  'update:publicVisible': [value: boolean]
  /** API 呼び出しエラー時に親に通知する */
  error: [message: string]
}>()

const { t } = useI18n()

/** ローカルのトグル状態（楽観的更新用） */
const localVisible = ref(props.publicVisible)

/** ローディング中フラグ */
const loading = ref(false)

/** エラーメッセージ */
const errorMessage = ref<string | null>(null)

/** props の変更を追跡して localVisible を同期する */
watch(
  () => props.publicVisible,
  (newVal) => {
    localVisible.value = newVal
  },
)

/**
 * トグルスイッチ変更ハンドラ。
 *
 * TODO: バックエンド API 実装後にコメントアウト部分を有効化する。
 * 現在は楽観的更新のみ行い、親コンポーネントに変更を通知する。
 */
async function handleToggle(newValue: boolean) {
  if (loading.value) return

  const previousValue = localVisible.value

  // TODO: Phase 3 以降で以下のリクエスト本体を使い API を呼び出す
  // const request: PublicVisiblePatchRequest = { publicVisible: newValue }

  // 楽観的更新: UI を即座に反映する
  localVisible.value = newValue
  errorMessage.value = null
  loading.value = true

  try {
    // TODO: Phase 3 以降で以下の API 呼び出しを有効化する
    // await $fetch(`/api/v1/admin/posts/${props.postId}/public-visible`, {
    //   method: 'PATCH',
    //   body: _request,
    // })

    // 現時点では楽観的更新のみ（API 未実装のため即時成功扱い）
    emit('update:publicVisible', newValue)
  } catch {
    // ロールバック: エラー時は元の状態に戻す
    localVisible.value = previousValue
    errorMessage.value = t('public.admin.publicVisible.saveFailed')
    emit('error', errorMessage.value)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex items-center gap-2">
    <!-- 公開/非公開ラベル -->
    <span
      :class="[
        'text-sm font-medium',
        localVisible ? 'text-green-600' : 'text-gray-400',
      ]"
    >
      {{
        localVisible
          ? t('public.admin.publicVisible.enabledLabel')
          : t('public.admin.publicVisible.disabledLabel')
      }}
    </span>

    <!-- トグルスイッチ -->
    <InputSwitch
      v-model="localVisible"
      :disabled="loading"
      :aria-label="t('public.admin.publicVisible.toggleAriaLabel')"
      :input-id="`public-visible-toggle-${postId}`"
      @update:model-value="handleToggle"
    />

    <!-- ローディングインジケーター -->
    <ProgressSpinner
      v-if="loading"
      style="width: 16px; height: 16px"
      stroke-width="4"
      :aria-label="t('public.admin.publicVisible.label')"
    />
  </div>

  <!-- エラーメッセージ -->
  <p
    v-if="errorMessage"
    class="mt-1 text-xs text-red-500"
    role="alert"
  >
    {{ errorMessage }}
  </p>
</template>
