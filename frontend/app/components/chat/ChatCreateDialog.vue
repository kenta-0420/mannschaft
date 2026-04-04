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

// タブ: 0=連絡先, 1=Zimmer作成
const activeTab = ref(0)

// === 連絡先タブ ===
const members = ref<MemberResponse[]>([])
const membersLoading = ref(false)
const selectedMembers = ref<MemberResponse[]>([])
const memberSearch = ref('')
const startingChat = ref(false)

const filteredMembers = computed(() => {
  const me = authStore.currentUser?.id
  const q = memberSearch.value.toLowerCase()
  return members.value
    .filter((m) => m.userId !== me)
    .filter((m) => !q || m.displayName.toLowerCase().includes(q))
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

function toggleMember(m: MemberResponse) {
  const idx = selectedMembers.value.findIndex((s) => s.userId === m.userId)
  if (idx >= 0) {
    selectedMembers.value.splice(idx, 1)
  } else {
    selectedMembers.value.push(m)
  }
}

function isMemberSelected(m: MemberResponse): boolean {
  return selectedMembers.value.some((s) => s.userId === m.userId)
}

const chatTypeLabel = computed(() =>
  selectedMembers.value.length <= 1 ? 'Kabine(DM)' : 'Zimmer(部屋)',
)

async function startChat() {
  if (selectedMembers.value.length === 0 || startingChat.value) return
  startingChat.value = true
  try {
    if (selectedMembers.value.length === 1) {
      // 1人 → Kabine(DM)
      const res = await getOrCreateDm(selectedMembers.value[0].userId)
      notification.success('Kabine(DM)を開きました')
      visible.value = false
      selectedMembers.value = []
      memberSearch.value = ''
      emit('openDm', res.data.id)
      emit('created')
    } else {
      // 2人以上 → Zimmer(部屋)自動作成
      const memberNames = selectedMembers.value.map((m) => m.displayName).join(', ')
      const channelType: ChatChannelType = props.teamId
        ? 'TEAM'
        : props.organizationId
          ? 'ORGANIZATION'
          : 'CROSS_TEAM'
      await createChannel({
        channelType,
        teamId: props.teamId,
        organizationId: props.organizationId,
        name: memberNames,
        memberIds: selectedMembers.value.map((m) => m.userId),
      })
      notification.success('Zimmer(部屋)を作成しました')
      visible.value = false
      selectedMembers.value = []
      memberSearch.value = ''
      emit('created')
    }
  } catch {
    notification.error('会話の作成に失敗しました')
  } finally {
    startingChat.value = false
  }
}

// === Zimmer作成タブ ===
const name = ref('')
const description = ref('')
const isPrivate = ref(false)
const submitting = ref(false)
const zimmerSelectedMembers = ref<MemberResponse[]>([])
const zimmerMemberSearch = ref('')

const zimmerFilteredMembers = computed(() => {
  const me = authStore.currentUser?.id
  const q = zimmerMemberSearch.value.toLowerCase()
  return members.value
    .filter((m) => m.userId !== me)
    .filter((m) => !q || m.displayName.toLowerCase().includes(q))
})

function toggleZimmerMember(m: MemberResponse) {
  const idx = zimmerSelectedMembers.value.findIndex((s) => s.userId === m.userId)
  if (idx >= 0) {
    zimmerSelectedMembers.value.splice(idx, 1)
  } else {
    zimmerSelectedMembers.value.push(m)
  }
}

function isZimmerMemberSelected(m: MemberResponse): boolean {
  return zimmerSelectedMembers.value.some((s) => s.userId === m.userId)
}

const channelType = computed<ChatChannelType>(() => {
  if (props.teamId) return 'TEAM'
  if (props.organizationId) return 'ORGANIZATION'
  return 'CROSS_TEAM'
})

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
      memberIds: zimmerSelectedMembers.value.map((m) => m.userId),
    })
    notification.success('Zimmer(部屋)を作成しました')
    visible.value = false
    name.value = ''
    description.value = ''
    isPrivate.value = false
    zimmerSelectedMembers.value = []
    zimmerMemberSearch.value = ''
    emit('created')
  } catch {
    notification.error('Zimmer(部屋)の作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

// ダイアログ表示時にメンバー読み込み
watch(visible, (v) => {
  if (v && members.value.length === 0) {
    loadMembers()
  }
  if (v) {
    activeTab.value = 0
    selectedMembers.value = []
    memberSearch.value = ''
    zimmerSelectedMembers.value = []
    zimmerMemberSearch.value = ''
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
        <!-- 連絡先タブ -->
        <TabPanel :value="0">
          <div class="mt-3 flex flex-col gap-3">
            <!-- 検索 -->
            <InputText v-model="memberSearch" placeholder="名前で検索..." class="w-full" />

            <!-- 選択中のメンバー -->
            <div v-if="selectedMembers.length > 0" class="flex flex-wrap gap-1">
              <Tag
                v-for="m in selectedMembers"
                :key="m.userId"
                :value="m.displayName"
                severity="info"
                class="cursor-pointer"
                @click="toggleMember(m)"
              >
                <template #default>
                  {{ m.displayName }}
                  <i class="pi pi-times ml-1 text-xs" />
                </template>
              </Tag>
            </div>

            <!-- 振り分け表示 -->
            <div
              v-if="selectedMembers.length > 0"
              class="rounded-lg bg-surface-100 px-3 py-2 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
            >
              <i class="pi pi-info-circle mr-1" />
              {{ selectedMembers.length }}人選択中 → <strong>{{ chatTypeLabel }}</strong
              >として開始されます
            </div>

            <!-- メンバー一覧 -->
            <div
              class="max-h-64 overflow-y-auto rounded-lg border border-surface-200 dark:border-surface-700"
            >
              <div v-if="membersLoading" class="flex justify-center py-6">
                <ProgressSpinner style="width: 30px; height: 30px" />
              </div>
              <div
                v-else-if="filteredMembers.length === 0"
                class="py-6 text-center text-sm text-surface-400"
              >
                メンバーが見つかりません
              </div>
              <button
                v-for="m in filteredMembers"
                :key="m.userId"
                class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
                :class="isMemberSelected(m) ? 'bg-primary/10' : ''"
                @click="toggleMember(m)"
              >
                <Avatar
                  :image="m.avatarUrl ?? undefined"
                  :label="m.avatarUrl ? undefined : m.displayName.charAt(0)"
                  shape="circle"
                  size="small"
                />
                <span class="flex-1 truncate text-sm">{{ m.displayName }}</span>
                <i v-if="isMemberSelected(m)" class="pi pi-check-circle text-primary" />
              </button>
            </div>
          </div>

          <!-- フッター -->
          <div class="mt-4 flex justify-end gap-2">
            <Button label="キャンセル" text @click="visible = false" />
            <Button
              :label="`${chatTypeLabel}を開始`"
              :loading="startingChat"
              :disabled="selectedMembers.length === 0"
              @click="startChat"
            />
          </div>
        </TabPanel>

        <!-- Zimmer作成タブ -->
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

            <!-- メンバー招待 -->
            <div>
              <label class="mb-1 block text-sm font-medium">メンバーを招待（任意）</label>
              <InputText
                v-model="zimmerMemberSearch"
                placeholder="名前で検索..."
                class="w-full mb-2"
              />
              <div v-if="zimmerSelectedMembers.length > 0" class="flex flex-wrap gap-1 mb-2">
                <Tag
                  v-for="m in zimmerSelectedMembers"
                  :key="m.userId"
                  severity="info"
                  class="cursor-pointer"
                  @click="toggleZimmerMember(m)"
                >
                  <template #default>
                    {{ m.displayName }}
                    <i class="pi pi-times ml-1 text-xs" />
                  </template>
                </Tag>
              </div>
              <div
                class="max-h-40 overflow-y-auto rounded-lg border border-surface-200 dark:border-surface-700"
              >
                <div v-if="membersLoading" class="flex justify-center py-4">
                  <ProgressSpinner style="width: 24px; height: 24px" />
                </div>
                <div
                  v-else-if="zimmerFilteredMembers.length === 0"
                  class="py-4 text-center text-sm text-surface-400"
                >
                  メンバーが見つかりません
                </div>
                <button
                  v-for="m in zimmerFilteredMembers"
                  :key="m.userId"
                  class="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-surface-100 dark:hover:bg-surface-700"
                  :class="isZimmerMemberSelected(m) ? 'bg-primary/10' : ''"
                  @click="toggleZimmerMember(m)"
                >
                  <Avatar
                    :image="m.avatarUrl ?? undefined"
                    :label="m.avatarUrl ? undefined : m.displayName.charAt(0)"
                    shape="circle"
                    size="small"
                  />
                  <span class="flex-1 truncate text-sm">{{ m.displayName }}</span>
                  <i v-if="isZimmerMemberSelected(m)" class="pi pi-check-circle text-primary" />
                </button>
              </div>
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
