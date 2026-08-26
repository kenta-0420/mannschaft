<script setup lang="ts">
import type { TimelineScopeType, TimelineDeliveryScope } from '~/types/timeline'
import { DEFAULT_DELIVERY_SCOPE } from '~/types/timeline'

const props = defineProps<{
  scopeType: TimelineScopeType
  /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
  scopeId?: string | number
}>()

const emit = defineEmits<{
  posted: []
}>()

const { createPost, getImageUploadUrl } = useTimelineApi()
const { showSuccess, showError } = useNotification()
const { t, te } = useI18n()

// お知らせウィジェット表示フラグ（チーム/組織スコープのみ有効）
const displayInAnnouncement = ref(false)
const isTeamOrOrgScope = computed(() =>
  (props.scopeType === 'TEAM' || props.scopeType === 'ORGANIZATION') && !!props.scopeId,
)

/**
 * 配下配信範囲（CMP-058）。組織スコープでのみ意味を持つ。
 * 既定は DIRECT（この団体のメンバーだけ）で、ユーザーが何もしなければこの値のまま送信される。
 */
const deliveryScope = ref<TimelineDeliveryScope>(DEFAULT_DELIVERY_SCOPE)

const isOrganizationScope = computed(() => props.scopeType === 'ORGANIZATION' && !!props.scopeId)

/**
 * 配信範囲の選択可否は組織のロールで決まる（ADMIN / DEPUTY_ADMIN のみ）。
 * 権限が無い人に選択肢を見せると BE が COMMON_002(403) で必ず弾き、意味の分からない失敗体験になるため、
 * 正典の {@link useRoleAccess} でロールを取得して可視制御する。
 * 取得に失敗した場合は roleName が null のままとなり選択肢は出ない（見せて弾かれるより安全側）。
 */
const orgScopeIdForRole = computed(() => (isOrganizationScope.value ? String(props.scopeId) : ''))
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgScopeIdForRole)

onMounted(() => {
  if (isOrganizationScope.value) void loadPermissions()
})

/**
 * 配信範囲セレクタの表示条件。
 * 組織スコープ かつ ADMIN/DEPUTY_ADMIN のときのみ。
 * （返信は本フォームでは作成されない — 返信は TimelinePostCard のインラインフォーム経由で、
 *   BE が親投稿から deliveryScope を継承するためクライアントは指定しない。）
 */
const canSelectDeliveryScope = computed(() => isOrganizationScope.value && isAdminOrDeputy.value)

const content = ref('')
const images = ref<File[]>([])
const videoUrl = ref('')
const showPollDialog = ref(false)
const poll = ref<{ question: string; options: string[]; expiresAt?: string } | null>(null)
const submitting = ref(false)

// 動画ファイル添付状態
const videoFileKey = ref<string | null>(null)
const videoFileName = ref<string | null>(null)
const videoFileSize = ref<number | null>(null)
const videoContentType = ref<string | null>(null)

const maxLength = computed(() => props.scopeType === 'PUBLIC' ? 280 : 5000)

// スコープ別のプレースホルダ（未知のscopeTypeはTEAM文言にフォールバック）
const composerPlaceholder = computed(() => {
  const key = `timeline.composerPlaceholder.${props.scopeType}`
  return te(key) ? t(key) : t('timeline.composerPlaceholder.TEAM')
})

const canSubmit = computed(() => {
  return content.value.trim().length > 0
    && content.value.length <= maxLength.value
    && !submitting.value
})

function onFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files) return
  const newFiles = Array.from(input.files)
  const total = images.value.length + newFiles.length
  if (total > 4) {
    showError(t('timeline.imageTooMany'))
    return
  }
  images.value.push(...newFiles.slice(0, 4 - images.value.length))
  input.value = ''
}

function removeImage(index: number) {
  images.value.splice(index, 1)
}

function onPollCreated(p: { question: string; options: string[]; expiresAt?: string }) {
  poll.value = p
}

function removePoll() {
  poll.value = null
}

function onVideoUploaded(payload: { fileKey: string; fileName: string; fileSize: number; contentType: string }) {
  videoFileKey.value = payload.fileKey
  videoFileName.value = payload.fileName
  videoFileSize.value = payload.fileSize
  videoContentType.value = payload.contentType
  showSuccess(t('timeline.videoUploadSuccess'))
}

function onVideoUploadError(message: string) {
  showError(message || t('timeline.videoUploadError'))
}

function removeVideo() {
  videoFileKey.value = null
  videoFileName.value = null
  videoFileSize.value = null
  videoContentType.value = null
}

function createObjectURL(file: File): string {
  return URL.createObjectURL(file)
}

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const isVillage = props.scopeType === 'VILLAGE'
    const scopeId = isVillage ? 0 : (props.scopeId ?? 0)

    // 1. 画像を事前アップロード（presigned URL方式）
    const imageAttachments: Record<string, unknown>[] = []
    for (const img of images.value) {
      const urlRes = await getImageUploadUrl({
        contentType: img.type,
        scopeType: props.scopeType,
        scopeId,
      })
      // R2 に直接 PUT（動画と同じパターン）
      const putRes = await fetch(urlRes.data.uploadUrl, {
        method: 'PUT',
        body: img,
        headers: { 'Content-Type': img.type },
      })
      if (!putRes.ok) {
        showError(t('timeline.imageUploadError'))
        return
      }
      imageAttachments.push({
        attachmentType: 'IMAGE',
        fileKey: urlRes.data.fileKey,
        originalFilename: img.name,
        fileSize: img.size,
        mimeType: img.type,
      })
    }

    // 2. 添付リスト（画像 + 動画ファイル + 動画リンク）を構築
    const attachments: Record<string, unknown>[] = [...imageAttachments]
    if (videoFileKey.value) {
      attachments.push({
        attachmentType: 'VIDEO_FILE',
        fileKey: videoFileKey.value,
        originalFilename: videoFileName.value,
        fileSize: videoFileSize.value,
        mimeType: videoContentType.value,
        videoProcessingStatus: 'PENDING',
      })
    }
    if (videoUrl.value.trim()) {
      // 外部動画URL（YouTube等）は VIDEO_LINK attachment として送信
      attachments.push({
        attachmentType: 'VIDEO_LINK',
        videoUrl: videoUrl.value.trim(),
      })
    }

    // 3. JSON ボディを構築して送信（BE @RequestBody はcamelCase。プロジェクト規約）
    const body: Record<string, unknown> = {
      scopeType: props.scopeType,
      scopeId: scopeId,
      // VILLAGE スコープ: scopeId=0 + scopeVillageId=UUID（設計書 §3.12.2）
      ...(isVillage && props.scopeId ? { scopeVillageId: String(props.scopeId) } : {}),
      content: content.value.trim(),
      // 配下配信範囲（CMP-058）。既定 DIRECT のときは送らない（BE 側の既定と一致するため）。
      // 権限が無い場合はセレクタ自体が出ず deliveryScope は既定のままなので、403 になる値は送られない。
      ...(canSelectDeliveryScope.value && deliveryScope.value !== DEFAULT_DELIVERY_SCOPE
        ? { deliveryScope: deliveryScope.value }
        : {}),
      ...(attachments.length ? { attachments } : {}),
      ...(poll.value ? { poll: poll.value } : {}),
    }

    const res = await createPost(body)
    // お知らせウィジェットに表示する場合、投稿後に登録（VILLAGE スコープは非対応）
    if (displayInAnnouncement.value && isTeamOrOrgScope.value && res?.data?.id && props.scopeId != null) {
      const { createAnnouncement } = useAnnouncementFeed(
        props.scopeType as 'TEAM' | 'ORGANIZATION',
        String(props.scopeId),
      )
      await createAnnouncement({
        sourceType: 'TIMELINE_POST',
        sourceId: res.data.id,
      }).catch(() => {
        showError(t('timeline.announcementRegisterError'))
      })
    }
    showSuccess(t('timeline.postSuccess'))
    content.value = ''
    images.value = []
    videoUrl.value = ''
    videoFileKey.value = null
    videoFileName.value = null
    videoFileSize.value = null
    videoContentType.value = null
    poll.value = null
    displayInAnnouncement.value = false
    deliveryScope.value = DEFAULT_DELIVERY_SCOPE
    emit('posted')
  } catch {
    showError(t('timeline.postError'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="rounded-xl border-2 border-surface-400 bg-surface-0 p-4 dark:bg-surface-800">
    <Textarea
      v-model="content"
      :placeholder="composerPlaceholder"
      auto-resize
      rows="3"
      class="mb-2 w-full"
      data-testid="team-timeline-composer"
    />

    <!-- 文字数カウンター -->
    <div class="mb-2 flex justify-end">
      <span
        class="text-xs"
        :class="content.length > maxLength ? 'text-red-500' : 'text-surface-400'"
      >
        {{ content.length }} / {{ maxLength }}
      </span>
    </div>

    <!-- 画像プレビュー -->
    <div v-if="images.length > 0" class="mb-3 flex flex-wrap gap-2">
      <div v-for="(img, i) in images" :key="i" class="relative">
        <img :src="createObjectURL(img)" class="h-20 w-20 rounded-lg object-cover" >
        <button
          class="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-xs text-white"
          @click="removeImage(i)"
        >
          <i class="pi pi-times text-[8px]" />
        </button>
      </div>
    </div>

    <!-- 動画プレビュー -->
    <div v-if="videoFileKey" class="mb-3 flex items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 p-3">
      <i class="pi pi-video text-primary" />
      <div class="flex-1 min-w-0">
        <p class="truncate text-sm font-medium">{{ videoFileName }}</p>
        <p class="text-xs text-surface-400">
          {{ videoFileSize ? `${Math.round(videoFileSize / 1024)} KB` : '' }}
        </p>
      </div>
      <Button
        icon="pi pi-times"
        text
        rounded
        severity="danger"
        size="small"
        :title="$t('timeline.videoRemove')"
        @click="removeVideo"
      />
    </div>

    <!-- 投票プレビュー -->
    <div v-if="poll" class="mb-3 rounded-lg border border-primary/30 bg-primary/5 p-3">
      <div class="flex items-center justify-between">
        <div>
          <p class="text-sm font-medium">{{ poll.question }}</p>
          <p class="text-xs text-surface-400">{{ $t('timeline.pollOptionsCount', { count: poll.options.length }) }}</p>
        </div>
        <Button icon="pi pi-times" text rounded severity="danger" size="small" @click="removePoll" />
      </div>
    </div>

    <!-- アクションバー -->
    <div class="flex items-center justify-between">
      <div class="flex items-center gap-1">
        <label class="cursor-pointer">
          <input type="file" accept="image/*" multiple class="hidden" @change="onFileSelect" >
          <Button icon="pi pi-image" text rounded severity="secondary" size="small" as="span" />
        </label>
        <!-- 動画アップロードボタン（VIDEO_FILE が未添付の場合のみ表示） -->
        <VideoUploadInput
          v-if="!videoFileKey"
          :scope-type="scopeType"
          :scope-id="scopeId"
          :disabled="submitting"
          @uploaded="onVideoUploaded"
          @error="onVideoUploadError"
        />
        <Button
          v-if="scopeType !== 'PUBLIC'"
          icon="pi pi-chart-bar"
          text
          rounded
          severity="secondary"
          size="small"
          :disabled="!!poll"
          @click="showPollDialog = true"
        />
      </div>
      <Button
        :label="$t('timeline.submitLabel')"
        size="small"
        :loading="submitting"
        :disabled="!canSubmit"
        data-testid="team-timeline-submit"
        @click="onSubmit"
      />
    </div>

    <!-- 配信範囲（組織スコープ かつ ADMIN/DEPUTY_ADMIN のみ・既定 DIRECT のまま触らなくてよい） -->
    <div v-if="canSelectDeliveryScope" class="mt-3 border-t border-surface-200 pt-3 dark:border-surface-700">
      <TimelineDeliveryScopeSelect v-model="deliveryScope" :disabled="submitting" />
    </div>

    <!-- お知らせウィジェット表示フラグ（チーム/組織スコープのみ） -->
    <div v-if="isTeamOrOrgScope" class="mt-3 border-t border-surface-200 pt-3 dark:border-surface-700">
      <AnnouncementToggle v-model="displayInAnnouncement" :disabled="submitting" />
    </div>

    <TimelinePollForm v-model:visible="showPollDialog" @created="onPollCreated" />
  </div>
</template>
