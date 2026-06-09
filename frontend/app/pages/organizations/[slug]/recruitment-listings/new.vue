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

const orgSlug = computed(() => String(route.params.slug))
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
    const result = await api.createOrgListing(orgSlug.value, fullBody)
    // F22.1 市: visibility=PUBLIC の札は publish 時に配信対象へ PUBLIC_FEED を含むことが必須（BE §5.1 RECRUITMENT_207）。
    //   BE の create は配信対象を保存しない設計のため、作成直後に PUBLIC_FEED を配信対象として登録しておく。
    //   これを怠ると publish が RECRUITMENT_204（配信対象 0 件）で失敗し、PUBLIC 札が市に出ない。
    //   組織スコープは FRIEND_TEAMS_ONLY を取れないため、市拡張の visibility は実質 PUBLIC 固定。
    if (marketVisibility.value === 'PUBLIC') {
      await api.setDistributionTargets(result.data.id, { targetTypes: ['PUBLIC_FEED'] })
    }
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
    <!-- 市拡張: 地域・公開範囲・フレンド宛先（ORGANIZATION スコープ） -->
    <div class="mb-6 rounded-lg border border-surface-200 bg-white p-4">
      <MarketListingFormExtension
        :scope-type="'ORGANIZATION'"
        :scope-id="orgSlug"
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
