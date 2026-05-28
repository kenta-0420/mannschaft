<script setup lang="ts">
interface Props {
  teamId: number
  name: string
  nameKana: string | null
  nickname1: string | null
  nickname2: string | null
  template: string
  templateLabel: string
  prefecture: string | null
  city: string | null
  visibility: string
  visibilityLabel: string
  memberCount: number
  teamFriendCount: number
  supporterCount: number
  supporterEnabled: boolean
  description: string | null
  isAdmin: boolean
  // F15.4 Phase 5-β: Google Maps 埋め込み URL
  mapEmbedUrl: string | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'updated:mapEmbedUrl', value: string | null): void
}>()

const { t } = useI18n()
const teamApi = useTeamApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

// F15.4 Phase 5-β: 地図 URL 編集状態
const mapEmbedUrlInput = ref<string>(props.mapEmbedUrl ?? '')
const mapEmbedUrlSaving = ref(false)

// 親から渡される props.mapEmbedUrl の変化に追従
watch(
  () => props.mapEmbedUrl,
  (val) => {
    mapEmbedUrlInput.value = val ?? ''
  },
)

// バリデーション: 空文字は OK（null として送信）、それ以外は Google Maps embed 形式必須
const mapEmbedUrlPattern = /^https:\/\/www\.google\.com\/maps\/embed\?.*$/
const mapEmbedUrlError = computed<string | null>(() => {
  const v = mapEmbedUrlInput.value.trim()
  if (!v) return null
  if (!mapEmbedUrlPattern.test(v)) {
    return t('team.mapEmbedUrlInvalidFormat')
  }
  return null
})

async function saveMapEmbedUrl() {
  if (mapEmbedUrlError.value) return
  mapEmbedUrlSaving.value = true
  try {
    const trimmed = mapEmbedUrlInput.value.trim()
    const payload = { mapEmbedUrl: trimmed === '' ? null : trimmed }
    const res = await teamApi.updateTeam(props.teamId, payload)
    const newUrl = res.data?.metadata?.mapEmbedUrl ?? null
    emit('updated:mapEmbedUrl', newUrl)
    notification.success(t('team.mapEmbedUrlSaved'))
  } catch (error) {
    handleApiError(error, t('team.mapEmbedUrlLabel'))
  } finally {
    mapEmbedUrlSaving.value = false
  }
}
</script>

<template>
  <div class="mt-4 grid grid-cols-1 gap-6 md:grid-cols-2">
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">チーム名</label>
        <p class="mt-1">
          {{ name }}
        </p>
      </div>
      <div v-if="nameKana">
        <label class="text-sm font-medium text-gray-500">チーム名（カナ）</label>
        <p class="mt-1">
          {{ nameKana }}
        </p>
      </div>
      <div v-if="nickname1">
        <label class="text-sm font-medium text-gray-500">ニックネーム1</label>
        <p class="mt-1">
          {{ nickname1 }}
        </p>
      </div>
      <div v-if="nickname2">
        <label class="text-sm font-medium text-gray-500">ニックネーム2</label>
        <p class="mt-1">
          {{ nickname2 }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">ジャンル</label>
        <p class="mt-1">
          {{ templateLabel }}
        </p>
      </div>
    </div>
    <div class="space-y-4">
      <div>
        <label class="text-sm font-medium text-gray-500">所在地</label>
        <p class="mt-1">
          {{ [prefecture, city].filter(Boolean).join(' ') || '未設定' }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">公開設定</label>
        <p class="mt-1">
          {{ visibilityLabel }}
        </p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">メンバー数</label>
        <p class="mt-1">{{ memberCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">{{ $t('label.teamFriendCount') }}</label>
        <p class="mt-1">{{ teamFriendCount }}チーム</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">{{ $t('label.supporterCount') }}</label>
        <p class="mt-1">{{ supporterCount }}人</p>
      </div>
      <div>
        <label class="text-sm font-medium text-gray-500">サポーター機能</label>
        <p class="mt-1">
          {{ supporterEnabled ? '有効' : '無効' }}
        </p>
      </div>
      <div v-if="description">
        <label class="text-sm font-medium text-gray-500">説明</label>
        <p class="mt-1 whitespace-pre-wrap">
          {{ description }}
        </p>
      </div>
    </div>
  </div>

  <!-- F15.4 Phase 5-β: 店舗地図埋め込み URL（管理者のみ編集可能） -->
  <section
    v-if="isAdmin"
    class="mt-6 rounded-xl border border-surface-200 bg-white p-6 shadow-sm dark:border-surface-700 dark:bg-surface-900"
    data-testid="team-map-embed-url-section"
  >
    <h3 class="mb-2 text-base font-semibold text-surface-700 dark:text-surface-200">
      {{ $t('team.mapEmbedUrlLabel') }}
    </h3>
    <p
      class="mb-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200"
    >
      {{ $t('team.mapEmbedUrlWarning') }}
    </p>
    <InputText
      v-model="mapEmbedUrlInput"
      class="w-full"
      :placeholder="$t('team.mapEmbedUrlPlaceholder')"
      :invalid="!!mapEmbedUrlError"
      data-testid="team-map-embed-url-input"
    />
    <p v-if="mapEmbedUrlError" class="mt-1 text-sm text-red-600 dark:text-red-400">
      {{ mapEmbedUrlError }}
    </p>
    <p class="mt-2 text-xs text-surface-500 dark:text-surface-400">
      {{ $t('team.mapEmbedUrlHelp') }}
    </p>
    <div class="mt-3 flex justify-end">
      <Button
        :label="$t('button.save')"
        icon="pi pi-check"
        :loading="mapEmbedUrlSaving"
        :disabled="!!mapEmbedUrlError"
        data-testid="team-map-embed-url-save"
        @click="saveMapEmbedUrl"
      />
    </div>
  </section>

  <TeamExtendedProfileDisplay
    :team-id="teamId"
    :is-admin-or-deputy="isAdmin"
  />
</template>
