<script setup lang="ts">
import type { SocialProfile } from '~/types/social-profile'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const handle = computed(() => route.params.handle as string)
const socialApi = useSocialProfileApi()
const notification = useNotification()

const profile = ref<SocialProfile | null>(null)
const loading = ref(true)
const activeTab = ref('0')

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await socialApi.getByHandle(handle.value)
  } catch {
    notification.error('プロフィールが見つかりませんでした')
  } finally {
    loading.value = false
  }
}

function handleFollowToggle(isFollowing: boolean) {
  if (profile.value) {
    profile.value = {
      ...profile.value,
      isFollowing,
      followerCount: profile.value.followerCount + (isFollowing ? 1 : -1),
    }
  }
}

onMounted(loadProfile)
watch(handle, loadProfile)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

    <template v-else-if="profile">
      <div class="mb-6 flex items-center gap-6">
        <img
          v-if="profile.avatarUrl"
          :src="profile.avatarUrl"
          alt=""
          class="h-24 w-24 rounded-full object-cover"
        />
        <div v-else class="flex h-24 w-24 items-center justify-center rounded-full bg-primary/10 text-3xl text-primary">
          <i class="pi pi-user" />
        </div>
        <div class="flex-1">
          <h1 class="text-2xl font-bold">{{ profile.displayName }}</h1>
          <p class="text-surface-500">@{{ profile.handle }}</p>
          <p v-if="profile.bio" class="mt-2 text-sm">{{ profile.bio }}</p>
          <div class="mt-3 flex items-center gap-4">
            <span class="text-sm"><strong>{{ profile.followerCount }}</strong> フォロワー</span>
            <span class="text-sm"><strong>{{ profile.followingCount }}</strong> フォロー中</span>
            <FollowButton
              followed-type="SOCIAL_PROFILE"
              :followed-id="profile.id"
              :is-following="profile.isFollowing ?? false"
              @toggle="handleFollowToggle"
            />
          </div>
        </div>
      </div>

      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="0">フォロワー</Tab>
          <Tab value="1">フォロー中</Tab>
        </TabList>
        <TabPanels>
          <TabPanel value="0">
            <FollowerList mode="followers" />
          </TabPanel>
          <TabPanel value="1">
            <FollowerList mode="following" />
          </TabPanel>
        </TabPanels>
      </Tabs>
    </template>

    <div v-else class="py-12 text-center text-surface-500">
      <i class="pi pi-user mb-2 text-4xl" />
      <p>プロフィールが見つかりませんでした</p>
    </div>
  </div>
</template>
