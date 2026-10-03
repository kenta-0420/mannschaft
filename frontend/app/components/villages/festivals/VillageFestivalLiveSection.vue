<script setup lang="ts">
/**
 * 祭詳細 — ACTIVE 中の実況セクション（F17.2 Wave2 ③お祭りの参加レイヤー §5.4/§5.6）。
 *
 * - ACTIVE 中のみ表示（SCHEDULED/ENDED では出さない・親が v-if で制御）
 * - 「この祭の実況として投稿する」ミニ投稿フォーム → 投稿後に自動で祭へタグ付けする
 *   （案B: 村ドメイン中間テーブル `village_festival_live_posts` に登録・timeline 本体は無改造）
 * - 実況一覧は `FestivalLivePostResponse`（`{festivalId, timelinePostId, createdAt}`）のみを
 *   持つため本文プレビューは無い。タイムライン詳細への恒久リンクとして表示する
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'
import type { VillageFestivalLivePostResponse } from '~/types/village'

defineProps<{
  livePosts: VillageFestivalLivePostResponse[]
  loading: boolean
  /** 実況投稿の作成ができるか（ACTIVE かつ村人） */
  canPost: boolean
  posting: boolean
}>()

const emit = defineEmits<{
  submit: [content: string]
}>()

const { t } = useI18n()
const { relativeTime } = useRelativeTime()

const content = ref('')

function submit() {
  const trimmed = content.value.trim()
  if (!trimmed) return
  emit('submit', trimmed)
  content.value = ''
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h3 class="font-semibold">
      {{ t('village.festival.live.title') }}
    </h3>

    <div v-if="canPost" class="flex flex-col gap-2">
      <Textarea
        v-model="content"
        :placeholder="t('village.festival.live.tagAsLive')"
        auto-resize
        rows="2"
        class="w-full"
        maxlength="5000"
      />
      <Button
        :label="t('village.festival.live.submit')"
        size="small"
        :disabled="!content.trim()"
        :loading="posting"
        class="self-end"
        @click="submit"
      />
    </div>

    <div v-if="loading" class="text-center py-3 text-surface-500">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="livePosts.length === 0" class="text-xs text-surface-500">
      {{ t('village.festival.live.empty') }}
    </div>
    <div v-else class="flex flex-col gap-1">
      <NuxtLink
        v-for="lp in livePosts"
        :key="lp.timelinePostId"
        :to="`/timeline/${lp.timelinePostId}`"
        class="flex items-center gap-2 rounded border border-surface-200 px-2 py-1 text-xs hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
      >
        <i class="pi pi-bolt text-primary" />
        <span>{{ t('village.festival.live.viewPost') }}</span>
        <span class="ml-auto text-surface-400 dark:text-surface-300">{{ relativeTime(lp.createdAt) }}</span>
      </NuxtLink>
    </div>
  </div>
</template>
