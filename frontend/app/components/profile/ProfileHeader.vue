<script setup lang="ts">
import type { ProfileMediaScope } from '~/types/profileMedia'

const props = withDefaults(
  defineProps<{
    /** アイコン（アバター）URL。null の場合はイニシャル表示 */
    iconUrl?: string | null
    /** バナー URL。null の場合はグラデーション背景 */
    bannerUrl?: string | null
    /** 表示名（イニシャル計算用） */
    name?: string
    /** スコープ種別 */
    scope: ProfileMediaScope
    /** スコープID（user の場合は null） */
    scopeId?: number | null
    /** 編集可能かどうか（trueならカメラアイコンオーバーレイを表示） */
    editable?: boolean
  }>(),
  {
    iconUrl: null,
    bannerUrl: null,
    name: '',
    scopeId: null,
    editable: false,
  },
)

const emit = defineEmits<{
  /** アップロード完了後、新しいURL（またはnull=削除）を通知 */
  iconUpdated: [url: string | null]
  bannerUpdated: [url: string | null]
}>()

const { t } = useI18n()
const notification = useNotification()
const { uploadAndCommit } = useProfileMediaApi()

const showIconCropModal = ref(false)
const uploadingBanner = ref(false)
const bannerUploadProgress = ref(0)
const bannerFileInput = ref<HTMLInputElement | null>(null)

/** バナー用ファイル選択を開く */
function openBannerPicker() {
  if (!props.editable) return
  bannerFileInput.value?.click()
}

/** バナーファイル選択後のアップロード */
async function handleBannerFileSelect(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return

  // バリデーション: GIF 不可
  if (file.type === 'image/gif') {
    notification.error(t('profile_media.banner_no_gif'))
    return
  }
  // バリデーション: 許可タイプ
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    notification.error(t('profile_media.unsupported_type'))
    return
  }
  // バリデーション: サイズ上限 10MB
  if (file.size > 10 * 1024 * 1024) {
    notification.error(t('profile_media.file_too_large_banner'))
    return
  }

  uploadingBanner.value = true
  bannerUploadProgress.value = 0
  try {
    const result = await uploadAndCommit(
      props.scope,
      props.scopeId ?? null,
      'banner',
      file,
      (progress) => {
        bannerUploadProgress.value = progress
      },
    )
    emit('bannerUpdated', result.url)
    notification.success(t('profile_media.upload_success'))
  }
  catch {
    notification.error(t('profile_media.upload_error'))
  }
  finally {
    uploadingBanner.value = false
    bannerUploadProgress.value = 0
    // 同じファイルを再選択できるようにリセット
    if (bannerFileInput.value) bannerFileInput.value.value = ''
  }
}

/** アイコンアップロード完了ハンドラ（IconCropModal から） */
function handleIconUploaded(url: string) {
  emit('iconUpdated', url)
  notification.success(t('profile_media.upload_success'))
}

/** イニシャル生成（最大2文字） */
const initials = computed(() => {
  if (!props.name) return '?'
  const words = props.name.trim().split(/\s+/)
  if (words.length >= 2) return ((words[0]![0] ?? '') + (words[1]![0] ?? '')).toUpperCase()
  return props.name.slice(0, 2).toUpperCase()
})

/** イニシャル背景色（name のハッシュから選択） */
const ICON_COLORS = [
  'bg-blue-500',
  'bg-green-500',
  'bg-purple-500',
  'bg-orange-500',
  'bg-red-500',
  'bg-teal-500',
  'bg-pink-500',
  'bg-indigo-500',
]

const initialsColorClass = computed(() => {
  let hash = 0
  for (const ch of props.name) hash = (hash * 31 + ch.charCodeAt(0)) & 0xfffffff
  return ICON_COLORS[hash % ICON_COLORS.length]
})
</script>

<template>
  <div class="profile-header relative">
    <!-- バナー領域 -->
    <div
      class="relative w-full overflow-hidden"
      :class="{ 'cursor-pointer': editable }"
      style="height: 200px"
      @click="openBannerPicker"
    >
      <!-- バナー画像 -->
      <img
        v-if="bannerUrl"
        :src="bannerUrl"
        :alt="t('profile_media.banner')"
        class="w-full h-full object-cover object-center"
      >
      <!-- バナーなし: グラデーション背景 -->
      <div
        v-else
        class="w-full h-full bg-gradient-to-r from-primary-400 to-primary-600"
      />

      <!-- 編集オーバーレイ（バナー） — アップロード中は非表示 -->
      <div
        v-if="editable && !uploadingBanner"
        class="absolute inset-0 bg-black/0 hover:bg-black/30 transition-colors flex items-center justify-center group"
      >
        <i class="pi pi-camera text-2xl text-white opacity-0 group-hover:opacity-100 transition-opacity" />
      </div>

      <!-- アップロード中インジケーター -->
      <div
        v-if="uploadingBanner"
        class="absolute inset-0 bg-black/40 flex flex-col items-center justify-center gap-2"
      >
        <ProgressSpinner style="width: 40px; height: 40px" />
        <span class="text-white text-sm">{{ t('profile_media.uploading') }}</span>
      </div>

      <!-- 非表示ファイル入力 -->
      <input
        ref="bannerFileInput"
        type="file"
        class="hidden"
        accept="image/jpeg,image/png,image/webp"
        @change="handleBannerFileSelect"
      >
    </div>

    <!-- アイコン領域（バナー下端に重なって表示） -->
    <div
      class="absolute left-6"
      style="bottom: -40px"
    >
      <div
        class="relative w-20 h-20 rounded-full overflow-hidden border-4 border-white shadow-md"
        :class="{ 'cursor-pointer': editable }"
        @click="editable && (showIconCropModal = true)"
      >
        <!-- アイコン画像 -->
        <img
          v-if="iconUrl"
          :src="iconUrl"
          :alt="t('profile_media.icon')"
          class="w-full h-full object-cover"
        >
        <!-- イニシャルフォールバック -->
        <div
          v-else
          class="w-full h-full flex items-center justify-center text-white font-bold text-xl"
          :class="initialsColorClass"
        >
          {{ initials }}
        </div>

        <!-- 編集オーバーレイ（アイコン） -->
        <div
          v-if="editable"
          class="absolute inset-0 bg-black/0 hover:bg-black/40 transition-colors flex items-center justify-center group"
        >
          <i class="pi pi-camera text-white opacity-0 group-hover:opacity-100 transition-opacity" />
        </div>
      </div>
    </div>

    <!-- アイコントリミングモーダル -->
    <IconCropModal
      v-model:visible="showIconCropModal"
      :scope="scope"
      :scope-id="scopeId ?? undefined"
      @uploaded="handleIconUploaded"
    />
  </div>
</template>
