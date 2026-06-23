<script setup lang="ts">
// ページ遷移中に 200ms 以上かかる場合のみスピナーを表示する。
// 高速遷移でのフラッシュを防ぐため、isLoading が true になってから 200ms 経過した
// タイミングで初めて show を true にし、遷移完了（isLoading = false）で即座に非表示にする。
const SHOW_DELAY_MS = 200

const { isLoading } = useLoadingIndicator()
const show = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

watch(isLoading, (loading) => {
  if (loading) {
    timer = setTimeout(() => {
      show.value = true
    }, SHOW_DELAY_MS)
  } else {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    show.value = false
  }
})

onBeforeUnmount(() => {
  if (timer !== null) {
    clearTimeout(timer)
  }
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="show"
        class="pointer-events-none fixed inset-0 z-[9998] flex items-center justify-center"
      >
        <ProgressSpinner style="width: 48px; height: 48px" />
      </div>
    </Transition>
  </Teleport>
</template>
