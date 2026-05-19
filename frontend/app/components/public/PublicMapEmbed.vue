<script setup lang="ts">
/**
 * F19.1 Google Maps iframe 表示コンポーネント。
 *
 * `map_embed_url` は BE で `^https://www\.google\.com/maps/embed\?` バリデーション済みだが、
 * フロントでも防御的に URL を検査する（Defense in Depth）。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §10.7 / §17.8
 */
const props = defineProps<{
  url: string | null | undefined
  title: string
}>()

const ALLOWED_PREFIX = 'https://www.google.com/maps/embed?'

const safeUrl = computed<string | null>(() => {
  const url = props.url
  if (!url) return null
  if (!url.startsWith(ALLOWED_PREFIX)) {
    if (import.meta.dev) {
      console.warn('[PublicMapEmbed] Rejected non-allow-listed URL:', url.slice(0, 80))
    }
    return null
  }
  return url
})
</script>

<template>
  <div v-if="safeUrl" class="overflow-hidden rounded-lg">
    <div class="aspect-video w-full">
      <iframe
        :src="safeUrl"
        :title="title"
        class="h-full w-full border-0"
        loading="lazy"
        referrerpolicy="no-referrer-when-downgrade"
        allowfullscreen
      />
    </div>
  </div>
</template>
