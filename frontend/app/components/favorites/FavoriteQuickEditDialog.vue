<script setup lang="ts">
// F02.9 お気に入り クイック編集ダイアログ（設計書 §5.3 B案）
// entityType に応じてフォームと保存先 API を切り替える。
// - TEAM:         PATCH /api/v1/teams/{id}（useTeamApi.updateTeam）
// - ORGANIZATION: PATCH /api/v1/organizations/{id}（useOrganizationApi.updateOrganization）
// - BLOG_AUTHOR:  PATCH /api/v1/social/profiles/me（useSocialProfileApi.updateMyProfile）
//   ※ ブログ著者プロフィールは社会プロファイル(SocialProfile)上で displayName/bio を保持するため
// - KB_PAGE:      フォーム表示せず即座に編集モード遷移（API 呼び出しなし）
// - VILLAGE:      F17.1 未実装のため保存ボタン disabled スタブ
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { navigateTo } from '#app'
import type { UserFavoriteItem } from '~/types/favorite'

interface Props {
  modelValue: UserFavoriteItem | null
}
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: UserFavoriteItem | null]
  saved: []
}>()

const { t } = useI18n()
const { updateTeam } = useTeamApi()
const { updateOrganization } = useOrganizationApi()
const { updateMyProfile: updateSocialProfile } = useSocialProfileApi()
const notification = useNotification()

const isOpen = computed({
  get: () => props.modelValue !== null,
  set: (v: boolean) => {
    if (!v) emit('update:modelValue', null)
  },
})

// === フォーム状態 ===
// TEAM / ORGANIZATION / VILLAGE 共通
const formName = ref('')
const formDescription = ref('')
const formIconUrl = ref('')
// BLOG_AUTHOR
const formDisplayName = ref('')
const formBio = ref('')

const isSaving = ref(false)
const errorMessage = ref<string | null>(null)

// 必須項目バリデーション
const isValid = computed(() => {
  const item = props.modelValue
  if (!item) return false
  switch (item.entityType) {
    case 'TEAM':
    case 'ORGANIZATION':
      return formName.value.trim().length > 0
    case 'BLOG_AUTHOR':
      return formDisplayName.value.trim().length > 0
    case 'VILLAGE':
      return false // F17.1 まで保存不可
    case 'KB_PAGE':
      return false // フォーム経由で保存することはない（mounted で navigateTo 済み）
    default:
      return false
  }
})

// 編集対象が変わったら値をプリフィル。KB_PAGE はその場で編集モードへ遷移してダイアログを閉じる。
watch(
  () => props.modelValue,
  (item) => {
    if (!item) return
    formName.value = item.entity.name ?? ''
    formDescription.value = item.entity.description ?? ''
    formIconUrl.value = item.entity.iconUrl ?? ''
    formDisplayName.value = item.entity.name ?? ''
    formBio.value = item.entity.description ?? ''
    errorMessage.value = null

    if (item.entityType === 'KB_PAGE') {
      const url = item.entity.pageUrl ?? ''
      if (url) {
        const sep = url.includes('?') ? '&' : '?'
        navigateTo(`${url}${sep}mode=edit`)
      }
      emit('update:modelValue', null)
    }
  },
  { immediate: true },
)

async function handleSave() {
  const item = props.modelValue
  if (!item || !isValid.value) return
  isSaving.value = true
  errorMessage.value = null
  try {
    await saveByType(item)
    notification.success(t('favorites.addSuccess'))
    emit('saved')
    emit('update:modelValue', null)
  } catch (e: unknown) {
    errorMessage.value = e instanceof Error ? e.message : t('favorites.saveError')
  } finally {
    isSaving.value = false
  }
}

async function saveByType(item: UserFavoriteItem) {
  switch (item.entityType) {
    case 'TEAM': {
      const teamId = String(item.entityId)
      await updateTeam(teamId, {
        name: formName.value.trim(),
        description: formDescription.value || null,
        iconUrl: formIconUrl.value || null,
      })
      return
    }
    case 'ORGANIZATION': {
      const orgId = String(item.entityId)
      await updateOrganization(orgId, {
        name: formName.value.trim(),
        description: formDescription.value || null,
      })
      return
    }
    case 'BLOG_AUTHOR': {
      // 自分の SocialProfile を更新する。handle は変更しない（既存値の維持はバックエンド側 PATCH 仕様に委ねる）。
      await updateSocialProfile({
        displayName: formDisplayName.value.trim(),
        bio: formBio.value || undefined,
      })
      return
    }
    case 'VILLAGE':
      // F17.1 未実装。設計書 §5.3 B案。
      throw new Error('VILLAGE 編集は F17.1 実装後に対応')
    case 'KB_PAGE':
      // KB_PAGE は watch 内で navigateTo 済み。ここに到達することはない。
      return
  }
}

function close() {
  emit('update:modelValue', null)
}
</script>

<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
    role="dialog"
    aria-modal="true"
    @click.self="close"
  >
    <div class="w-full max-w-md rounded-lg bg-white p-6 shadow-xl dark:bg-gray-800">
      <h2 class="mb-4 text-lg font-bold text-gray-900 dark:text-gray-100">
        {{ t('favorites.quickEditTitle') }}
      </h2>

      <div
        v-if="errorMessage"
        class="mb-4 rounded bg-red-100 p-2 text-sm text-red-700 dark:bg-red-900 dark:text-red-200"
        role="alert"
      >
        {{ errorMessage }}
      </div>

      <!-- TEAM -->
      <template v-if="modelValue?.entityType === 'TEAM'">
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">名前 <span class="text-red-500">*</span></span>
          <input
            v-model="formName"
            type="text"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            required
          >
        </label>
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">説明</span>
          <textarea
            v-model="formDescription"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            rows="3"
          />
        </label>
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">アイコンURL</span>
          <input
            v-model="formIconUrl"
            type="text"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
          >
        </label>
      </template>

      <!-- ORGANIZATION -->
      <template v-else-if="modelValue?.entityType === 'ORGANIZATION'">
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">名前 <span class="text-red-500">*</span></span>
          <input
            v-model="formName"
            type="text"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            required
          >
        </label>
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">説明</span>
          <textarea
            v-model="formDescription"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            rows="3"
          />
        </label>
      </template>

      <!-- BLOG_AUTHOR（自分の SocialProfile を編集） -->
      <template v-else-if="modelValue?.entityType === 'BLOG_AUTHOR'">
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">表示名 <span class="text-red-500">*</span></span>
          <input
            v-model="formDisplayName"
            type="text"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            required
          >
        </label>
        <label class="mb-3 block">
          <span class="mb-1 block text-sm font-medium">自己紹介</span>
          <textarea
            v-model="formBio"
            class="w-full rounded border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700"
            rows="3"
          />
        </label>
      </template>

      <!-- VILLAGE スタブ（F17.1 待ち） -->
      <template v-else-if="modelValue?.entityType === 'VILLAGE'">
        <p class="mb-4 text-sm text-gray-600 dark:text-gray-300">
          村機能（F17.1）の実装が完了するまでお待ちください。
        </p>
      </template>

      <div class="mt-4 flex justify-end gap-2">
        <button
          type="button"
          class="rounded border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-100 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
          @click="close"
        >
          {{ t('favorites.cancel') }}
        </button>
        <button
          type="button"
          class="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="isSaving || !isValid"
          @click="handleSave"
        >
          {{ isSaving ? '...' : t('favorites.save') }}
        </button>
      </div>
    </div>
  </div>
</template>
