<script setup lang="ts">
/**
 * F06.5 構造化入力フォーム（§2.3）。
 *
 * アウトライン固定 5 階層（main_theme → section[heading] → subsection[sub_heading/detail/supplement]）
 * ＋ free_note（マスク対象外）を編集する。v-model で ReflectionStructuredContent を双方向バインドする。
 */
import {
  type ReflectionStructuredContent,
  emptySection,
  emptySubsection,
} from '~/types/reflection'

const props = defineProps<{
  modelValue: ReflectionStructuredContent
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ReflectionStructuredContent]
}>()

const { t } = useI18n()

// ローカルコピーで編集し、変更ごとに親へ通知（深い変更も拾うため deep watch）。
const local = ref<ReflectionStructuredContent>(props.modelValue)

watch(
  () => props.modelValue,
  (v) => {
    if (v !== local.value) local.value = v
  },
)

watch(
  local,
  (v) => emit('update:modelValue', v),
  { deep: true },
)

function addSection() {
  local.value.sections.push(emptySection())
}

function removeSection(idx: number) {
  local.value.sections.splice(idx, 1)
}

function addSubsection(sectionIdx: number) {
  local.value.sections[sectionIdx]?.subsections.push(emptySubsection())
}

function removeSubsection(sectionIdx: number, subIdx: number) {
  local.value.sections[sectionIdx]?.subsections.splice(subIdx, 1)
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
      class="rounded-xl border border-surface-200 p-3 dark:border-surface-700"
    >
      <div class="mb-2 flex items-center gap-2">
        <InputText
          v-model="section.heading"
          :placeholder="t('reflection.entry.section_heading_placeholder')"
          class="flex-1"
          maxlength="200"
        />
        <Button
          icon="pi pi-trash"
          severity="danger"
          text
          rounded
          :aria-label="t('reflection.entry.remove_section')"
          @click="removeSection(si)"
        />
      </div>

      <!-- 小見出し（subsection）一覧 -->
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
