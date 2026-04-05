<script setup lang="ts">
const props = defineProps<{
  activityType: string
  actorName: string
  actorAvatarUrl: string | null
  targetTitle: string
  scopeName: string
  createdAt: string
}>()

const relativeTime = useRelativeTime(toRef(props, 'createdAt'))

const activityConfig: Record<string, { icon: string; verb: string }> = {
  POST_CREATED: { icon: 'pi pi-pencil', verb: 'が投稿しました' },
  EVENT_CREATED: { icon: 'pi pi-calendar-plus', verb: 'がイベントを作成しました' },
  MEMBER_JOINED: { icon: 'pi pi-user-plus', verb: 'が参加しました' },
  TODO_COMPLETED: { icon: 'pi pi-check-circle', verb: 'がTODOを完了しました' },
  BULLETIN_CREATED: { icon: 'pi pi-megaphone', verb: 'がお知らせを投稿しました' },
  POLL_CREATED: { icon: 'pi pi-chart-bar', verb: 'がアンケートを作成しました' },
  FILE_UPLOADED: { icon: 'pi pi-upload', verb: 'がファイルを共有しました' },
}

const config = computed(() => activityConfig[props.activityType] ?? { icon: 'pi pi-circle', verb: 'がアクションしました' })
</script>

<template>
  <div class="flex items-start gap-3 py-2">
    <Avatar
      :image="actorAvatarUrl"
      :label="actorAvatarUrl ? undefined : actorName.charAt(0)"
      shape="circle"
      size="normal"
    />
    <div class="min-w-0 flex-1">
      <p class="text-sm">
        <span class="font-medium">{{ actorName }}</span>
        <span class="text-surface-500">{{ config.verb }}</span>
      </p>
      <p class="truncate text-sm text-surface-600 dark:text-surface-400">
        <i :class="config.icon" class="mr-1 text-xs" />
        {{ targetTitle }}
      </p>
      <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
        <span>{{ scopeName }}</span>
        <span>·</span>
        <span>{{ relativeTime }}</span>
      </div>
    </div>
  </div>
</template>
