<script setup lang="ts">
import type { ParentLinkResponse, ChildLinkResponse } from '@/types/parental-consent'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const { getParents, removeParent, getChildren, removeChild } = useParentalConsentApi()
const notification = useNotification()
const { formatDate } = useDatetime()

const parents = ref<ParentLinkResponse[]>([])
const children = ref<ChildLinkResponse[]>([])
const loading = ref(true)

async function loadData() {
  loading.value = true
  try {
    const [p, c] = await Promise.all([getParents(), getChildren()])
    parents.value = p
    children.value = c
  } finally {
    loading.value = false
  }
}

async function handleRemoveParent(linkId: string) {
  try {
    await removeParent(linkId)
    notification.success(t('parental_consent.link_removed'))
    await loadData()
  } catch (err: unknown) {
    const code = (err as { data?: { code?: string } })?.data?.code ?? ''
    if (code === 'AUTH_064') {
      notification.error(t('parental_consent.error_auth_064'))
    } else {
      notification.error(t('common.error.unknown'))
    }
  }
}

async function handleRemoveChild(linkId: string) {
  try {
    await removeChild(linkId)
    notification.success(t('parental_consent.link_removed'))
    await loadData()
  } catch (err: unknown) {
    const code = (err as { data?: { code?: string } })?.data?.code ?? ''
    if (code === 'AUTH_065') {
      notification.error(t('parental_consent.error_auth_065'))
    } else {
      notification.error(t('common.error.unknown'))
    }
  }
}

onMounted(loadData)
</script>

<template>
  <div class="max-w-2xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">{{ $t('parental_consent.manage_title') }}</h1>

    <div v-if="loading" class="text-center py-8 text-gray-400">...</div>
    <template v-else>
      <!-- 承認済み保護者一覧 -->
      <section class="mb-8">
        <h2 class="text-lg font-semibold mb-3">{{ $t('parental_consent.parents_title') }}</h2>
        <p v-if="parents.length === 0" class="text-gray-400">{{ $t('parental_consent.no_parents') }}</p>
        <ul v-else class="space-y-3">
          <li
            v-for="p in parents"
            :key="p.linkId"
            class="bg-white border border-gray-200 rounded-lg p-4 flex items-center justify-between"
          >
            <div>
              <p class="font-medium">{{ p.parentEmail }}</p>
              <p class="text-sm text-gray-500">{{ formatDate(p.approvedAt) }}</p>
            </div>
            <button
              class="text-sm text-red-600 hover:text-red-800"
              @click="handleRemoveParent(p.linkId)"
            >
              {{ $t('parental_consent.remove_parent') }}
            </button>
          </li>
        </ul>
      </section>

      <!-- 保護者として監護している子一覧 -->
      <section>
        <h2 class="text-lg font-semibold mb-3">{{ $t('parental_consent.children_title') }}</h2>
        <p v-if="children.length === 0" class="text-gray-400">{{ $t('parental_consent.no_children') }}</p>
        <ul v-else class="space-y-3">
          <li
            v-for="c in children"
            :key="c.linkId"
            class="bg-white border border-gray-200 rounded-lg p-4 flex items-center justify-between"
          >
            <div>
              <p class="font-medium">{{ c.childDisplayName ?? '—' }}</p>
              <p class="text-sm text-gray-500">{{ formatDate(c.approvedAt) }}</p>
            </div>
            <button
              class="text-sm text-red-600 hover:text-red-800"
              @click="handleRemoveChild(c.linkId)"
            >
              {{ $t('parental_consent.remove_child') }}
            </button>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
