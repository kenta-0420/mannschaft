<script setup lang="ts">
import type { TimelinePostResponse } from '~/types/timeline'

const props = defineProps<{ post: TimelinePostResponse }>()
const emit = defineEmits<{ edited: [] }>()
const auth = useAuthStore()
const { t } = useI18n()
const { updatePost } = useTimelineApi()
const { handleApiError } = useErrorHandler()
const team = useRoleAccess('team', props.post.scope.scopeType === 'TEAM' ? String(props.post.scope.scopeId) : '')
const organization = useRoleAccess('organization', props.post.scope.scopeType === 'ORGANIZATION' ? String(props.post.scope.scopeId) : '')
const access = props.post.scope.scopeType === 'TEAM' ? team : organization
const restricted = computed(() => ['TEAM', 'ORGANIZATION'].includes(props.post.scope.scopeType))
// 変身中も実効ユーザーで本人判定する。元管理者のIDは使用しない。
const ordinaryOwnPost = computed(() => auth.currentUser?.id === props.post.author.userId
  && props.post.content.status === 'PUBLISHED'
  && props.post.content.parentId == null && props.post.content.repostOfId == null
  && !props.post.repostOf && !props.post.systemPostType
  && (props.post.author.postedAsType == null || ['USER', 'SOCIAL_PROFILE'].includes(props.post.author.postedAsType)))
const permissionReady = ref(false)
const canEdit = computed(() => ordinaryOwnPost.value && (!restricted.value || (permissionReady.value
  && access.roleName.value != null
  && (access.roleName.value !== 'MEMBER' || access.can('MANAGE_POSTS')))))
const visible = ref(false)
const content = ref('')
const saving = ref(false)
const maxLength = computed(() => props.post.scope.scopeType === 'PUBLIC' ? 280 : 5000)
const valid = computed(() => !!content.value.trim() && content.value.length <= maxLength.value)
let active = true

onMounted(async () => {
  if (!ordinaryOwnPost.value || !restricted.value) return
  const result = await access.loadPermissions()
  if (!active) return
  if (result.ok) permissionReady.value = true
  else handleApiError(result.error)
})
onUnmounted(() => { active = false })
watch(canEdit, allowed => { if (!allowed) visible.value = false }, { flush: 'sync' })

function open() {
  if (!canEdit.value) return
  content.value = props.post.content.content ?? ''
  visible.value = true
}

async function save() {
  if (!canEdit.value || !valid.value || saving.value) return
  saving.value = true
  try {
    // BE の PATCH は本文だけを受け取り、添付・投票・スコープ・公開範囲を変更しない。
    await updatePost(props.post.id, { content: content.value.trim() })
    if (!active || !canEdit.value) return
    visible.value = false
    emit('edited') // 表示用情報を含む GET で再取得する。PATCH の DTO では置換しない。
  } catch (error) {
    if (active) handleApiError(error)
  } finally {
    if (active) saving.value = false
  }
}
</script>

<template>
  <span v-if="canEdit" @click.stop>
    <Button :label="t('button.edit')" icon="pi pi-pencil" text size="small" class="min-h-11 min-w-11" data-testid="timeline-post-edit" @click="open" />
    <Dialog v-model:visible="visible" :header="t('button.edit')" modal :closable="!saving" :close-on-escape="!saving" :style="{ width: 'min(500px, 90vw)' }">
      <Textarea v-model="content" :aria-label="t(`timeline.composerPlaceholder.${post.scope.scopeType}`)" :maxlength="maxLength" auto-resize rows="4" class="w-full text-base" data-testid="timeline-edit-content" />
      <p class="text-sm text-surface-500">{{ content.length }} / {{ maxLength }}</p>
      <template #footer>
        <Button :label="t('button.cancel')" text :disabled="saving" data-testid="timeline-edit-cancel" @click="visible = false" />
        <Button :label="t('button.save')" :disabled="!valid || saving" :loading="saving" data-testid="timeline-edit-save" @click="save" />
      </template>
    </Dialog>
  </span>
</template>
