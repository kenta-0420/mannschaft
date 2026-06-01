<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type {
  CreateRecruitmentListingRequest,
  RecruitmentCategoryResponse,
} from '~/types/recruitment'
import type { FriendTargetInput, RegionInput } from '~/types/market'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const teamId = computed(() => Number(route.params.id))
const categories = ref<RecruitmentCategoryResponse[]>([])
const loading = ref(false)

// F22.1 市（Market）拡張フィールド
const marketPrefectureCode = ref<string | null>(null)
const marketCityCode = ref<string | null>(null)
const marketRegions = ref<RegionInput[]>([])
const marketVisibility = ref<'PUBLIC' | 'FRIEND_TEAMS_ONLY'>('PUBLIC')
const marketFriendTargets = ref<FriendTargetInput[]>([])

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
    // 市拡張フィールドをマージ（visibility は MarketListingFormExtension が管理）
    const fullBody: CreateRecruitmentListingRequest = {
      ...body,
      visibility: marketVisibility.value,
      prefectureCode: marketPrefectureCode.value,
      cityCode: marketCityCode.value,
      regions: marketRegions.value,
      friendTargets: marketVisibility.value === 'FRIEND_TEAMS_ONLY' ? marketFriendTargets.value : [],
    }
    const result = await api.createListing(teamId.value, fullBody)
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
    <PageHeader :title="t('market.page.newListing')" class="mb-4" />
    <!-- 市拡張: 地域・公開範囲・フレンド宛先 -->
    <div class="mb-6 rounded-lg border border-surface-200 bg-white p-4">
      <MarketListingFormExtension
        :scope-type="'TEAM'"
        :scope-id="teamId"
        @update:prefecture-code="marketPrefectureCode = $event"
        @update:city-code="marketCityCode = $event"
        @update:regions="marketRegions = $event"
        @update:visibility="marketVisibility = $event"
        @update:friend-targets="marketFriendTargets = $event"
      />
    </div>
    <!-- 既存フォーム（visibility セレクタを非表示: 市拡張側で管理） -->
    <RecruitmentListingForm
      :categories="categories"
      :loading="loading"
      :hide-visibility="true"
      @submit="onSubmit"
    />
  </div>
</template>
