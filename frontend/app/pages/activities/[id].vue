<script setup lang="ts">
import type { ActivityDetailResponse } from '~/types/activity'
import { activityDisplayFields, activityFileIds } from '~/utils/activityDetail'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
const { getActivity, publishActivity } = useActivityApi()
const { getFileMetadata, getDownloadUrl } = useFileSharingApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const { renderMarkdown } = useMarkdownRenderer()
const record = ref<ActivityDetailResponse | null>(null)
const { deleting, requestDelete } = useActivityDeletion(record)
const loading = ref(true)
const error = shallowRef<unknown>(null)
const showEdit = ref(false)
const publishing = ref(false)
const files = ref<Array<{ id: number; name: string; disabled: boolean }>>([])
const fields = computed(() =>
  record.value && !record.value.metadataOnly ? activityDisplayFields(record.value) : [],
)
const descriptionHtml = computed(() =>
  renderMarkdown(record.value?.metadataOnly ? '' : (record.value?.description ?? '')),
)
let loadGeneration = 0
useHead(() => ({ title: record.value?.title ?? t('activity.pageTitle') }))

async function load(): Promise<void> {
  const generation = ++loadGeneration
  loading.value = true
  error.value = null
  record.value = null
  files.value = []
  try {
    const id = Number(route.params.id)
    if (!Number.isSafeInteger(id) || id <= 0) throw createError({ statusCode: 404 })
    const result = (await getActivity(id)).data
    if (!result.metadataOnly) activityDisplayFields(result)
    const ids = result.metadataOnly ? [] : activityFileIds(result.attachments)
    if (generation !== loadGeneration) return
    record.value = result
    // 添付自体の認可も既存storage APIに委ねる。失敗は本文取得と区別して通知する。
    for (const fileId of ids) {
      try {
        const file = (await getFileMetadata(fileId)).data
        if (generation !== loadGeneration) return
        files.value.push({
          id: fileId,
          name: file.name ?? t('activity.detail.attachment', { id: fileId }),
          disabled: file.downloadDisabled === true,
        })
      } catch (fileError) {
        if (generation !== loadGeneration) return
        files.value.push({
          id: fileId,
          name: t('activity.detail.attachment', { id: fileId }),
          disabled: true,
        })
        handleApiError(fileError, '活動記録添付情報')
      }
    }
  } catch (loadError) {
    if (generation !== loadGeneration) return
    error.value = loadError
    handleApiError(loadError, '活動記録詳細')
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
async function download(id: number): Promise<void> {
  try {
    const result = await getDownloadUrl(id)
    window.open(result.data.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (downloadError) {
    handleApiError(downloadError, '活動記録添付閲覧')
  }
}
async function publish(): Promise<void> {
  if (!record.value?.canPublish || record.value.metadataOnly || publishing.value || deleting.value)
    return
  publishing.value = true
  try {
    if (record.value.version === null) throw new Error('活動記録の更新版を取得できません')
    await publishActivity(record.value.id, record.value.version)
    notification.success(t('activity.publish.success'))
    await load()
  } catch (publishError) {
    handleApiError(publishError, '活動記録公開')
  } finally {
    publishing.value = false
  }
}
onMounted(load)
watch(() => route.params.id, load)
</script>

<template>
  <div class="min-w-0 space-y-4">
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <template v-else-if="record">
      <PageHeader :title="record.title" />
      <Button
        v-if="record.scopePublicId"
        class="min-h-11 min-w-11"
        :label="t('activity.pageTitle')"
        icon="pi pi-arrow-left"
        text
        @click="
          navigateTo(
            `/${record.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${record.scopePublicId}/activities`,
          )
        "
      />
      <div class="flex flex-wrap items-center gap-3">
        <ActivityStatusBadges :record="record" />
        <Button
          v-if="record.canEdit && !record.metadataOnly && record.status === 'DRAFT'"
          class="min-h-11 min-w-11"
          :label="t('activity.detail.edit')"
          icon="pi pi-pencil"
          data-testid="activity-edit-draft"
          :disabled="deleting || publishing"
          @click="showEdit = true"
        />
        <Button
          v-if="record.canPublish && !record.metadataOnly && record.status === 'DRAFT'"
          class="min-h-11 min-w-11"
          :label="t('activity.detail.publish')"
          icon="pi pi-send"
          :loading="publishing"
          data-testid="activity-publish"
          :disabled="deleting"
          @click="publish"
        />
        <Button
          v-if="record.canDelete && !record.metadataOnly"
          class="min-h-11 min-w-11"
          :label="t('activity.delete.action')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          :loading="deleting"
          :disabled="publishing"
          data-testid="activity-delete"
          @click="requestDelete"
        />
      </div>
      <p
        v-if="record.metadataOnly"
        class="text-sm text-surface-500"
        data-testid="activity-metadata-only"
      >
        {{ t('activity.metadataOnly') }}
      </p>
      <SectionCard>
        <p data-testid="activity-datetime">
          {{ record.activityDate }} {{ record.activityTimeStart }}
          <template v-if="record.activityEndDate || record.activityTimeEnd"
            >— {{ record.activityEndDate }} {{ record.activityTimeEnd }}</template
          >
        </p>
        <!-- 既存rendererはDOMPurifyでHTMLをsanitizeしてから返す。 -->
        <!-- eslint-disable vue/no-v-html -- 既存sanitize済みMarkdown描画 -->
        <div
          v-if="!record.metadataOnly && record.description"
          class="prose mt-3 max-w-none overflow-x-auto break-words dark:prose-invert"
          data-testid="activity-description"
          v-html="descriptionHtml"
        />
        <!-- eslint-enable vue/no-v-html -->
        <dl
          v-if="!record.metadataOnly"
          class="mt-4 space-y-2"
          data-testid="activity-template-fields"
        >
          <div v-for="field in fields" :key="field.key">
            <dt class="text-sm text-surface-500">{{ field.label }}</dt>
            <dd class="whitespace-pre-wrap break-words">{{ field.value }} {{ field.unit }}</dd>
          </div>
        </dl>
      </SectionCard>
      <SectionCard v-if="!record.metadataOnly" :title="t('activity.detail.participants')">
        <ul v-if="record.participants.length" class="space-y-2" data-testid="activity-participants">
          <li v-for="participant in record.participants" :key="participant.userId">
            {{ participant.displayName }}
            <span class="text-sm text-surface-500">{{ participant.roleLabel }}</span>
          </li>
        </ul>
        <DashboardEmptyState
          v-else
          icon="pi pi-users"
          :message="t('activity.detail.noParticipants')"
        />
      </SectionCard>
      <SectionCard
        v-if="!record.metadataOnly && files.length"
        :title="t('activity.detail.attachments')"
      >
        <Button
          v-for="file in files"
          :key="file.id"
          :label="file.name"
          :disabled="file.disabled"
          icon="pi pi-download"
          text
          class="min-h-11 min-w-11 break-all"
          @click="download(file.id)"
        />
      </SectionCard>
      <SectionCard v-if="record.sourceSchedule" :title="t('activity.detail.sourceSchedule')">
        <NuxtLink
          v-if="record.sourceSchedule.canView"
          :to="`/${record.sourceSchedule.scopeType === 'TEAM' ? 'teams' : 'organizations'}/${record.sourceSchedule.scopePublicId}/schedule?eventId=${record.sourceSchedule.id}`"
          class="inline-flex min-h-11 items-center underline"
          data-testid="activity-source-schedule"
          >{{ t('activity.detail.openSchedule') }}</NuxtLink
        >
        <p v-else>{{ t('activity.detail.scheduleUnavailable') }}</p>
        <p v-if="record.sourceSchedule.state === 'CANCELLED'">
          {{ t('activity.detail.scheduleCancelled') }}
        </p>
      </SectionCard>
      <ActivityEditDialog
        v-if="record.canEdit && !record.metadataOnly && record.status === 'DRAFT'"
        v-model:visible="showEdit"
        :record="record"
        @saved="load"
      />
    </template>
  </div>
</template>
