<script setup lang="ts">
import type { ChatChannelType } from '~/types/chat'
import type { MemberResponse } from '~/types/member'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  created: []
  openDm: [channelId: number]
}>()

const { createChannel, getOrCreateDm } = useChatApi()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const authStore = useAuthStore()
const notification = useNotification()

const activeTab = ref(0)
const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)

const currentUserId = computed(() => authStore.currentUser?.id)

const contact = useChatMemberSelect(members, currentUserId)
const zimmer = useChatMemberSelect(members, currentUserId)

const startingChat = ref(false)

const chatTypeLabel = computed(() =>
  contact.selected.value.length <= 1 ? 'Kabine(DM)' : 'Zimmer(部屋)',
)

const channelType = computed<ChatChannelType>(() => {
  if (props.teamId) return 'TEAM'
  if (props.organizationId) return 'ORGANIZATION'
  return 'CROSS_TEAM'
})

async function loadMembers() {
  membersLoading.value = true
  try {
    if (props.teamId) {
      const res = await teamApi.getMembers(props.teamId, { size: 200 })
      members.value = res.data
    } else if (props.organizationId) {
      const res = await orgApi.getMembers(props.organizationId, { size: 200 })
      members.value = res.data
    }
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
      notification.success('Kabine(DM)を開きました')
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
      notification.success('Zimmer(部屋)を作成しました')
      visible.value = false
      contact.reset()
      emit('created')
    }
  } catch {
    notification.error('会話の作成に失敗しました')
  } finally {
    startingChat.value = false
  }
}

const name = ref('')
const description = ref('')
const isPrivate = ref(false)
const submitting = ref(false)

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
    notification.success('Zimmer(部屋)を作成しました')
    visible.value = false
    name.value = ''
    description.value = ''
    isPrivate.value = false
    zimmer.reset()
    emit('created')
  } catch {
    notification.error('Zimmer(部屋)の作成に失敗しました')
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
  }
})
</script>

<template>
  <Dialog v-model:visible="visible" header="新しい会話を始める" modal class="w-full max-w-lg">
    <Tabs v-model:value="activeTab">
      <TabList>
        <Tab :value="0"> <i class="pi pi-users mr-1" />連絡先から選ぶ </Tab>
        <Tab :value="1"> <i class="pi pi-hashtag mr-1" />Zimmer(部屋)を作成 </Tab>
      </TabList>

      <TabPanels>
        <TabPanel :value="0">
          <div class="mt-3 flex flex-col gap-3">
            <InputText v-model="contact.search.value" placeholder="名前で検索..." class="w-full" />
            <ChatSelectedTags :members="contact.selected.value" @remove="contact.toggle" />
            <div
              v-if="contact.selected.value.length > 0"
              class="rounded-lg bg-surface-100 px-3 py-2 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
            >
              <i class="pi pi-info-circle mr-1" />
              {{ contact.selected.value.length }}人選択中 → <strong>{{ chatTypeLabel }}</strong
              >として開始されます
            </div>
            <ChatMemberSelectList
              :members="contact.filtered.value"
              :loading="membersLoading"
              :is-selected="contact.isSelected"
              @toggle="contact.toggle"
            />
          </div>
          <div class="mt-4 flex justify-end gap-2">
            <Button label="キャンセル" text @click="visible = false" />
            <Button
              :label="`${chatTypeLabel}を開始`"
              :loading="startingChat"
              :disabled="contact.selected.value.length === 0"
              @click="startChat"
            />
          </div>
        </TabPanel>

        <TabPanel :value="1">
          <div class="mt-3 flex flex-col gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">Zimmer(部屋)名</label>
              <InputText v-model="name" class="w-full" placeholder="例: general" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">説明（任意）</label>
              <InputText v-model="description" class="w-full" placeholder="Zimmer(部屋)の説明" />
            </div>
            <div class="flex items-center gap-2">
              <Checkbox v-model="isPrivate" :binary="true" input-id="private" />
              <label for="private" class="text-sm">プライベートZimmer(部屋)（招待制）</label>
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">メンバーを招待（任意）</label>
              <InputText
                v-model="zimmer.search.value"
                placeholder="名前で検索..."
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
            <Button label="キャンセル" text @click="visible = false" />
            <Button
              label="作成"
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
