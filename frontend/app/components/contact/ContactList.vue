<script setup lang="ts">
import type { ContactResponse } from '~/types/contact'

const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()
const notification = useNotification()

const contacts = ref<ContactResponse[]>([])
const loading = ref(false)
const total = ref(0)
const searchQuery = ref('')

async function fetchContacts() {
  loading.value = true
  try {
    const result = await contactApi.listContacts({ q: searchQuery.value || undefined, limit: 50 })
    contacts.value = result.data
    total.value = result.meta.total
  } catch (e) {
    captureQuiet(e, { context: 'ContactList: 連絡先一覧取得' })
  } finally {
    loading.value = false
  }
}

async function removeContact(userId: number) {
  try {
    await contactApi.deleteContact(userId)
    contacts.value = contacts.value.filter((c) => c.user.id !== userId)
    total.value--
    notification.success('連絡先を削除しました')
  } catch (e) {
    captureQuiet(e, { context: 'ContactList: 連絡先削除' })
    notification.error('削除に失敗しました')
  }
}

function openDm(userId: number) {
  navigateTo(`/chat?dm=${userId}`)
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(searchQuery, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(fetchContacts, 300)
})

onMounted(fetchContacts)
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="flex items-center gap-2">
      <IconField class="flex-1">
        <InputIcon class="pi pi-search" />
        <InputText v-model="searchQuery" placeholder="連絡先を検索..." class="w-full" />
      </IconField>
      <span class="shrink-0 text-sm text-gray-400">{{ total }}人</span>
    </div>

    <PageLoading v-if="loading" />

    <div v-else-if="contacts.length === 0" class="py-8 text-center text-sm text-gray-400">
      <i class="pi pi-users mb-2 block text-3xl" />
      <p v-if="searchQuery">「{{ searchQuery }}」に一致する連絡先がありません</p>
      <p v-else>連絡先がありません</p>
    </div>

    <div v-else class="flex flex-col">
      <ContactListItem
        v-for="contact in contacts"
        :key="contact.folderItemId"
        :contact="contact"
        @dm="openDm"
        @remove="removeContact"
      />
    </div>
  </div>
</template>
