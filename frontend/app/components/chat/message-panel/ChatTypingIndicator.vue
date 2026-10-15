<script setup lang="ts">
const props = defineProps<{
  typingUsers: Map<number, string>
}>()

const typingText = computed(() => {
  const names = Array.from(props.typingUsers.values())
  if (names.length === 0) return ''
  if (names.length === 1) return names[0]
  return names.join('、')
})

const isMultipleTyping = computed(() => props.typingUsers.size > 1)
</script>

<template>
  <div
    v-if="typingUsers.size > 0"
    class="px-4 py-1 text-sm italic text-surface-400 dark:text-surface-500"
  >
    {{
      isMultipleTyping
        ? $t('chat.typing.multiple', { names: typingText })
        : $t('chat.typing.single', { name: typingText })
    }}
  </div>
</template>
