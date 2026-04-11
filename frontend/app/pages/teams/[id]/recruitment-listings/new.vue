<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type {
  CreateRecruitmentListingRequest,
  RecruitmentCategoryResponse,
} from '~/types/recruitment'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const teamId = computed(() => Number(route.params.id))
const categories = ref<RecruitmentCategoryResponse[]>([])
const loading = ref(false)

async function loadCategories() {
  try {
    const result = await api.listCategories()
    categories.value = result.data
  }
  catch (e) {
    error(String(e))
  }
}

async function onSubmit(body: CreateRecruitmentListingRequest) {
  loading.value = true
  try {
    const result = await api.createListing(teamId.value, body)
    success(t('recruitment.action.create'))
    router.push(`/recruitment-listings/${result.data.id}`)
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

onMounted(() => loadCategories())
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <h1 class="mb-4 text-2xl font-bold">
      {{ t('recruitment.page.newListing') }}
    </h1>
    <RecruitmentListingForm
      :categories="categories"
      :loading="loading"
      @submit="onSubmit"
    />
  </div>
</template>
