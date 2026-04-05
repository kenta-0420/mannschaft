<script setup lang="ts">
const { t } = useI18n()

const faqCount = 5
const openFaqs = ref<Record<number, boolean>>({})

function toggle(i: number) {
  openFaqs.value[i] = !openFaqs.value[i]
}
</script>

<template>
  <section id="faq" aria-labelledby="faq-heading" class="bg-surface-50 py-24 dark:bg-surface-900">
    <div class="mx-auto max-w-3xl px-4">
      <div class="mb-14 text-center">
        <h2 id="faq-heading" class="text-3xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.faq.heading') }}
        </h2>
      </div>

      <div class="space-y-2">
        <div
          v-for="i in faqCount"
          :key="i"
          class="overflow-hidden rounded-xl border border-surface-200 bg-white dark:border-surface-700 dark:bg-surface-800"
        >
          <button
            :id="`faq-button-${i - 1}`"
            :aria-expanded="openFaqs[i - 1] ? 'true' : 'false'"
            :aria-controls="`faq-panel-${i - 1}`"
            class="flex w-full items-center justify-between gap-4 px-5 py-4 text-left font-semibold text-surface-800 transition-colors hover:bg-surface-50 dark:text-white dark:hover:bg-surface-700"
            @click="toggle(i - 1)"
          >
            <span>{{ t(`landing.faq.items.${i - 1}.q`) }}</span>
            <i
              :class="openFaqs[i - 1] ? 'pi pi-chevron-up' : 'pi pi-chevron-down'"
              class="shrink-0 text-sm text-surface-400"
            />
          </button>
          <Transition name="faq">
            <div
              v-if="openFaqs[i - 1]"
              :id="`faq-panel-${i - 1}`"
              role="region"
              :aria-labelledby="`faq-button-${i - 1}`"
              class="border-t border-surface-100 px-5 pb-5 pt-4 text-sm leading-relaxed text-surface-600 dark:border-surface-700 dark:text-surface-300"
            >
              {{ t(`landing.faq.items.${i - 1}.a`) }}
            </div>
          </Transition>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.faq-enter-active,
.faq-leave-active {
  transition: all 0.2s ease;
  max-height: 200px;
  overflow: hidden;
}
.faq-enter-from,
.faq-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
