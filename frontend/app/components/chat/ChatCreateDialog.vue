<script setup lang="ts">
import type { ChatChannelType } from '~/types/chat'
import type { MemberResponse } from '~/types/member'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  teamId?: string
  organizationId?: string
}>()

const emit = defineEmits<{
  created: []
  openDm: [channelId: number]
}>()

const { createChannel, getOrCreateDm } = useChatApi()
const { loadCandidates } = useChatCandidateMembers()
const authStore = useAuthStore()
const notification = useNotification()
const { t } = useI18n()

const activeTab = ref(0)
const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)

const currentUserId = computed(() => authStore.currentUser?.id)

const contact = useChatMemberSelect(members, currentUserId)
const zimmer = useChatMemberSelect(members, currentUserId)

const startingChat = ref(false)

// Zimmer（部屋）作成フォームの入力状態
const name = ref('')
const description = ref('')
const isPrivate = ref(false)
const submitting = ref(false)

// === useFormDraft（ADHD配慮・Zimmer作成フォームの自動保存）===
// teamId / organizationId が変わるとキーも変わるのでフォームスコープ分離される
const zimmerDraftKey = computed(() => {
  const scope = props.teamId
    ? `team-${props.teamId}`
    : props.organizationId
      ? `org-${props.organizationId}`
      : 'global'
  return `zimmer-create-draft-${authStore.currentUser?.id ?? 'guest'}-${scope}`
})

interface ZimmerDraftShape {
  name: string
  description: string
  isPrivate: boolean
}

const zimmerFormSnapshot = computed<ZimmerDraftShape>(() => ({
  name: name.value,
  description: description.value,
  isPrivate: isPrivate.value,
}))

const { clear: clearZimmerDraft, restore: restoreZimmerDraft } = useFormDraft<ZimmerDraftShape>(
  zimmerDraftKey.value,
  { source: zimmerFormSnapshot, debounceMs: 1000, autoRestore: false },
)

const chatTypeLabel = computed(() =>
  contact.selected.value.length <= 1 ? t('chat.create.typeDm') : t('chat.create.typeRoom'),
)

/**
 * 作成するチャンネルの種別を決定する。
 *
 * BE {@code ChannelType} の正準値に合わせる（valueOf で解決されるため不正値は 400/500）。
 * - チーム文脈: 非公開トグルで TEAM_PRIVATE / TEAM_PUBLIC
 * - 組織文脈: 非公開トグルで ORG_PRIVATE / ORG_PUBLIC
 * - どちらでもない（横断チャット）: 複数人グループDM = GROUP_DM
 */
const channelType = computed<ChatChannelType>(() => {
  if (props.teamId) return isPrivate.value ? 'TEAM_PRIVATE' : 'TEAM_PUBLIC'
  if (props.organizationId) return isPrivate.value ? 'ORG_PRIVATE' : 'ORG_PUBLIC'
  return 'GROUP_DM'
})

async function loadMembers() {
  membersLoading.value = true
  try {
    members.value = await loadCandidates({
      teamId: props.teamId,
      organizationId: props.organizationId,
    })
  } catch {
    members.value = []
  } finally {
    membersLoading.value = false
  }
}

async function startChat() {
  if (contact.selected.value.length === 0 || startingChat.value) return
  startingChat.value = true
  try {
    const firstMember = contact.selected.value[0]
    if (contact.selected.value.length === 1 && firstMember) {
      const res = await getOrCreateDm(firstMember.userId)
      notification.success(t('chat.create.dmOpened'))
      visible.value = false
      contact.reset()
      emit('openDm', res.data.id)
      emit('created')
    } else {
      const memberNames = contact.selected.value.map((m) => m.displayName).join(', ')
      await createChannel({
        channelType: channelType.value,
        teamId: props.teamId,
        organizationId: props.organizationId,
        name: memberNames,
        memberIds: contact.selected.value.map((m) => m.userId),
      })
      notification.success(t('chat.create.roomCreated'))
      visible.value = false
      contact.reset()
      emit('created')
    }
  } catch {
    notification.error(t('chat.create.conversationFailed'))
  } finally {
    startingChat.value = false
  }
}

async function onSubmitZimmer() {
  if (!name.value.trim() || submitting.value) return
  submitting.value = true
  try {
    await createChannel({
      channelType: channelType.value,
      teamId: props.teamId,
      organizationId: props.organizationId,
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      isPrivate: isPrivate.value,
      memberIds: zimmer.selected.value.map((m) => m.userId),
    })
    clearZimmerDraft()
    notification.success(t('chat.create.roomCreated'))
    visible.value = false
    name.value = ''
    description.value = ''
    isPrivate.value = false
    zimmer.reset()
    emit('created')
  } catch {
    notification.error(t('chat.create.roomCreateFailed'))
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => {
  if (v && members.value.length === 0) {
    loadMembers()
  }
  if (v) {
    activeTab.value = 0
    contact.reset()
    zimmer.reset()
    // Zimmer下書き復元
    const saved = restoreZimmerDraft()
    if (saved) {
      name.value = saved.name ?? ''
      description.value = saved.description ?? ''
      isPrivate.value = saved.isPrivate ?? false
    }
  }
})
</script>

<template>
  <Dialog v-model:visible="visible" :header="$t('chat.create.header')" modal class="w-full max-w-lg">
    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0"> <i class="pi pi-users mr-1" />{{ $t('chat.create.tabContacts') }} </Tab>
        <Tab :value="1"> <i class="pi pi-hashtag mr-1" />{{ $t('chat.create.tabCreateRoom') }} </Tab>
      </TabList>

      <TabPanels>
        <TabPanel :value="0">
          <div class="mt-3 flex flex-col gap-3">
            <InputText v-model="contact.search.value" :placeholder="$t('chat.create.searchByName')" class="w-full" />
            <ChatSelectedTags :members="contact.selected.value" @remove="contact.toggle" />
            <div
              v-if="contact.selected.value.length > 0"
              class="rounded-lg bg-surface-100 px-3 py-2 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
            >
              <i class="pi pi-info-circle mr-1" />
              {{ $t('chat.create.selectedSummary', { count: contact.selected.value.length, type: chatTypeLabel }) }}
            </div>
            <ChatMemberSelectList
              :members="contact.filtered.value"
              :loading="membersLoading"
              :is-selected="contact.isSelected"
              @toggle="contact.toggle"
            />
          </div>
          <div class="mt-4 flex justify-end gap-2">
            <Button :label="$t('common.cancel')" text @click="visible = false" />
            <Button
              :label="$t('chat.create.startAs', { type: chatTypeLabel })"
              :loading="startingChat"
              :disabled="contact.selected.value.length === 0"
              @click="startChat"
            />
          </div>
        </TabPanel>

        <TabPanel :value="1">
          <div class="mt-3 flex flex-col gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ $t('chat.create.roomName') }}</label>
              <InputText v-model="name" class="w-full" :placeholder="$t('chat.create.roomNamePlaceholder')" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ $t('chat.create.descriptionOptional') }}</label>
              <InputText v-model="description" class="w-full" :placeholder="$t('chat.create.descriptionPlaceholder')" />
            </div>
            <div class="flex items-center gap-2">
              <Checkbox v-model="isPrivate" :binary="true" input-id="private" />
              <label for="private" class="text-sm">{{ $t('chat.create.privateRoom') }}</label>
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ $t('chat.create.inviteMembersOptional') }}</label>
              <InputText
                v-model="zimmer.search.value"
                :placeholder="$t('chat.create.searchByName')"
                class="mb-2 w-full"
              />
              <ChatSelectedTags
                :members="zimmer.selected.value"
                class="mb-2"
                @remove="zimmer.toggle"
              />
              <ChatMemberSelectList
                :members="zimmer.filtered.value"
                :loading="membersLoading"
                :is-selected="zimmer.isSelected"
                max-height="max-h-40"
                spinner-size="24px"
                @toggle="zimmer.toggle"
              />
            </div>
          </div>
          <div class="mt-4 flex justify-end gap-2">
            <Button :label="$t('common.cancel')" text @click="visible = false" />
            <Button
              :label="$t('chat.create.create')"
              :loading="submitting"
              :disabled="!name.trim()"
              @click="onSubmitZimmer"
            />
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </Dialog>
</template>
