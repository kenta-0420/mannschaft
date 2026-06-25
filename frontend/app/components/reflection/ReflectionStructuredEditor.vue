<script setup lang="ts">
/**
 * F06.5 構造化入力フォーム（§2.3・Phase 4 §13-D/§13-E）。
 *
 * Phase 4 追加: TERM_CARD section（暗記カード）・section 折りたたみ（初期 collapsed）。
 */
import {
  type ReflectionStructuredContent,
  emptySection,
  emptySubsection,
  emptyCard,
} from '~/types/reflection'

const props = defineProps<{
  modelValue: ReflectionStructuredContent
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ReflectionStructuredContent]
}>()

const { t } = useI18n()

const local = ref<ReflectionStructuredContent>(props.modelValue)

// 各 section の折りたたみ状態（初期: true=collapsed）
const collapsed = ref<boolean[]>(local.value.sections.map(() => true))

watch(
  () => props.modelValue,
  (v) => {
    if (v !== local.value) {
      local.value = v
      collapsed.value = v.sections.map(() => true)
    }
  },
)

watch(
  local,
  (v) => emit('update:modelValue', v),
  { deep: true },
)

function addSection() {
  local.value.sections.push(emptySection())
  collapsed.value.push(false) // 新規追加は展開
}

function removeSection(idx: number) {
  local.value.sections.splice(idx, 1)
  collapsed.value.splice(idx, 1)
}

function toggleCollapse(idx: number) {
  collapsed.value[idx] = !collapsed.value[idx]
}

function addSubsection(sectionIdx: number) {
  local.value.sections[sectionIdx]?.subsections.push(emptySubsection())
}

function removeSubsection(sectionIdx: number, subIdx: number) {
  local.value.sections[sectionIdx]?.subsections.splice(subIdx, 1)
}

function addCard(sectionIdx: number) {
  const s = local.value.sections[sectionIdx]
  if (!s) return
  if (!s.cards) s.cards = []
  s.cards.push(emptyCard())
}

function removeCard(sectionIdx: number, cardIdx: number) {
  local.value.sections[sectionIdx]?.cards?.splice(cardIdx, 1)
}

function sectionSummary(sectionIdx: number): string {
  const s = local.value.sections[sectionIdx]
  if (!s) return ''
  if (s.type === 'TERM_CARD') {
    const n = s.cards?.length ?? 0
    return t('reflection.section.summary_cards', { n })
  }
  const n = s.subsections.length
  return t('reflection.section.summary_subsections', { n })
}
</script>

<template>
  <div class="space-y-4">
    <!-- メインテーマ -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('reflection.entry.main_theme_label') }}</label>
      <InputText
        v-model="local.main_theme"
        :placeholder="t('reflection.entry.main_theme_placeholder')"
        class="w-full"
        maxlength="200"
      />
    </div>

    <!-- 中見出し（section）一覧 -->
    <div
      v-for="(section, si) in local.sections"
      :key="si"
      class="rounded-xl border border-surface-200 dark:border-surface-700"
    >
      <!-- section ヘッダー行（常時表示）-->
      <div class="flex items-center gap-2 p-3">
        <button
          type="button"
          class="text-sm text-surface-500 hover:text-surface-700 dark:text-surface-400 dark:hover:text-surface-200"
          :aria-label="collapsed[si] ? t('reflection.section.expand') : t('reflection.section.collapse')"
          @click="toggleCollapse(si)"
        >
          {{ collapsed[si] ? '+' : '−' }}
        </button>
        <InputText
          v-model="section.heading"
          :placeholder="t('reflection.entry.section_heading_placeholder')"
          class="flex-1"
          maxlength="200"
        />
        <span v-if="collapsed[si]" class="text-xs text-surface-400">{{ sectionSummary(si) }}</span>
        <Button
          icon="pi pi-trash"
          severity="danger"
          text
          rounded
          :aria-label="t('reflection.entry.remove_section')"
          @click="removeSection(si)"
        />
      </div>

      <!-- section 本体（展開時のみ表示）-->
      <div v-if="!collapsed[si]" class="border-t border-surface-200 p-3 dark:border-surface-700">
        <!-- OUTLINE: 小見出し一覧 -->
        <template v-if="section.type !== 'TERM_CARD'">
          <div
            v-for="(sub, subi) in section.subsections"
            :key="subi"
            class="mb-2 space-y-2 rounded-lg bg-surface-50 p-2 dark:bg-surface-800"
          >
            <div class="flex items-center gap-2">
              <InputText
                v-model="sub.sub_heading"
                :placeholder="t('reflection.entry.sub_heading_placeholder')"
                class="flex-1"
                maxlength="200"
              />
              <Button
                icon="pi pi-times"
                severity="secondary"
                text
                rounded
                :aria-label="t('reflection.entry.remove_subsection')"
                @click="removeSubsection(si, subi)"
              />
            </div>
            <Textarea
              v-model="sub.detail"
              :placeholder="t('reflection.entry.detail_placeholder')"
              class="w-full"
              rows="2"
              auto-resize
              maxlength="2000"
            />
            <Textarea
              v-model="sub.supplement"
              :placeholder="t('reflection.entry.supplement_placeholder')"
              class="w-full"
              rows="1"
              auto-resize
              maxlength="2000"
            />
          </div>
          <Button
            :label="t('reflection.entry.add_subsection')"
            icon="pi pi-plus"
            size="small"
            text
            @click="addSubsection(si)"
          />
        </template>

        <!-- TERM_CARD: 暗記カード一覧 -->
        <template v-else>
          <div
            v-for="(card, ci) in section.cards ?? []"
            :key="ci"
            class="mb-2 rounded-lg bg-surface-50 p-2 dark:bg-surface-800"
          >
            <div class="flex items-start gap-2">
              <div class="flex-1 space-y-1">
                <InputText
                  v-model="card.term"
                  :placeholder="t('reflection.card.term_label')"
                  class="w-full"
                  maxlength="200"
                />
                <InputText
                  v-model="card.meaning"
                  :placeholder="t('reflection.card.meaning_label')"
                  class="w-full"
                  maxlength="200"
                />
              </div>
              <Button
                icon="pi pi-trash"
                severity="danger"
                text
                rounded
                :aria-label="t('reflection.card.remove')"
                @click="removeCard(si, ci)"
              />
            </div>
          </div>
          <Button
            :label="t('reflection.card.add')"
            icon="pi pi-plus"
            size="small"
            text
            @click="addCard(si)"
          />
        </template>
      </div>
    </div>

    <Button
      :label="t('reflection.entry.add_section')"
      icon="pi pi-plus"
      size="small"
      severity="secondary"
      outlined
      @click="addSection"
    />

    <!-- 自由メモ（マスク対象外） -->
    <div>
      <label class="mb-1 block text-sm font-medium">{{ t('reflection.entry.free_note_label') }}</label>
      <Textarea
        v-model="local.free_note"
        :placeholder="t('reflection.entry.free_note_placeholder')"
        class="w-full"
        rows="3"
        auto-resize
        maxlength="10000"
      />
    </div>
  </div>
</template>
