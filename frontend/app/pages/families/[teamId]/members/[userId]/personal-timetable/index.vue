<script setup lang="ts">
import type { FamilyPersonalTimetable } from '~/types/personal-timetable'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const api = useFamilyPersonalTimetableApi()
const { error } = useNotification()

const teamId = computed(() => Number(route.params.teamId))
const userId = computed(() => Number(route.params.userId))

const items = ref<FamilyPersonalTimetable[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try {
    items.value = await api.list(teamId.value, userId.value)
  }
  catch (e) {
    error(t('personalTimetable.familyView.list_load_error'), String(e))
    items.value = []
  }
  finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="p-4 md:p-6 max-w-4xl mx-auto">
    <header class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ t('personalTimetable.familyView.page_title') }}
      </h1>
      <p class="text-sm text-gray-500 mt-1">
        {{ t('personalTimetable.familyView.private_notice') }}
      </p>
    </header>

    <div v-if="loading" class="text-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="items.length === 0" class="text-center py-12 text-gray-500">
      {{ t('personalTimetable.familyView.list_empty') }}
    </div>

    <ul v-else class="space-y-3">
      <li
        v-for="pt in items"
        :key="pt.id"
        class="border rounded-lg p-4 bg-white shadow-sm hover:shadow transition"
      >
        <div class="flex items-center justify-between">
          <div>
            <h2 class="font-semibold">
              {{ pt.name }}
            </h2>
            <p class="text-xs text-gray-500 mt-1">
              {{ pt.effective_from }}
              <span v-if="pt.effective_until"> 〜 {{ pt.effective_until }}</span>
              <span v-if="pt.term_label"> / {{ pt.term_label }}</span>
            </p>
          </div>
          <NuxtLink
            :to="`/families/${teamId}/members/${userId}/personal-timetable/${pt.id}`"
            class="text-sm text-blue-600 hover:underline"
          >
            {{ t('personalTimetable.familyView.list_btn_open') }} →
          </NuxtLink>
        </div>
      </li>
    </ul>
  </div>
</template>
