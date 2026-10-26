<script setup lang="ts">
const { t } = useI18n()

const props = defineProps<{
  post: {
    id: number
    sourceTeamName: string
    content: string
    createdAt: string
    isForwarded?: boolean
  }
}>()

const emit = defineEmits<{
  forward: [postId: number]
}>()

const { formatRelative } = useRelativeTime()
</script>

<template>
  <SectionCard>
    <div class="flex flex-col gap-3">
      <!-- ヘッダ: チーム名・バッジ・日時 -->
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2">
          <i class="pi pi-users text-primary" />
          <span class="font-semibold">{{ props.post.sourceTeamName }}</span>
          <Tag severity="info" :value="t('friend_feed.badge')" class="text-xs" />
        </div>
        <span class="text-xs text-surface-400">
          {{ formatRelative(props.post.createdAt) }}
        </span>
      </div>

      <!-- 本文 -->
      <p class="text-sm leading-relaxed text-surface-700 dark:text-surface-300">
        {{ props.post.content }}
      </p>

      <!-- フッタ: 転送ボタン or 転送済みバッジ -->
      <div class="flex items-center justify-end gap-2">
        <Tag
          v-if="props.post.isForwarded"
          severity="success"
          :value="t('friend_feed.forwarded_badge')"
        />
        <Button
          v-else
          :label="t('friend_feed.forward.button')"
          icon="pi pi-share-alt"
          size="small"
          outlined
          @click="emit('forward', props.post.id)"
        />
      </div>
    </div>
  </SectionCard>
</template>
