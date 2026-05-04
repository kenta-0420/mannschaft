<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import type { ClassHomeroomCreateRequest, ClassHomeroomUpdateRequest } from '~/types/school'

const props = defineProps<{
  teamId: number
  academicYear: number
}>()

const teamIdRef = computed(() => props.teamId)
const { homerooms, loading, submitting, loadHomerooms, addHomeroom, editHomeroom } =
  useClassHomeroom(teamIdRef)

const showAddForm = ref(false)
const editingId = ref<number | null>(null)

// 新規追加フォーム
const newForm = ref<ClassHomeroomCreateRequest>({
  homeroomTeacherUserId: 0,
  assistantTeacherUserIds: [],
  academicYear: props.academicYear,
  effectiveFrom: new Date().toISOString().slice(0, 10),
  effectiveUntil: undefined,
})

// 編集フォーム（有効終了日のみ）
const editForm = ref<ClassHomeroomUpdateRequest>({
  effectiveUntil: undefined,
})

function isCurrentlyActive(effectiveFrom: string, effectiveUntil?: string): boolean {
  const today = new Date().toISOString().slice(0, 10)
  if (effectiveUntil && effectiveUntil < today) return false
  return effectiveFrom <= today
}

function resetNewForm(): void {
  newForm.value = {
    homeroomTeacherUserId: 0,
    assistantTeacherUserIds: [],
    academicYear: props.academicYear,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveUntil: undefined,
  }
  showAddForm.value = false
}

function startEdit(id: number, currentEffectiveUntil?: string): void {
  editingId.value = id
  editForm.value = { effectiveUntil: currentEffectiveUntil }
}

function cancelEdit(): void {
  editingId.value = null
  editForm.value = { effectiveUntil: undefined }
}

async function onAdd(): Promise<void> {
  if (!newForm.value.homeroomTeacherUserId) return
  const result = await addHomeroom(newForm.value)
  if (result) {
    resetNewForm()
  }
}

async function onUpdate(id: number): Promise<void> {
  const result = await editHomeroom(id, editForm.value)
  if (result) {
    cancelEdit()
  }
}

onMounted(() => {
  void loadHomerooms(props.academicYear)
})
</script>

<template>
  <div class="homeroom-assignment-panel">
    <div class="flex items-center justify-between mb-4">
      <h2 class="text-base font-semibold text-surface-800 dark:text-surface-100">
        {{ $t('school.homeroom.title') }}
      </h2>
      <Button
        :label="$t('school.homeroom.add')"
        size="small"
        @click="showAddForm = !showAddForm"
      />
    </div>

    <!-- 新規追加フォーム -->
    <div
      v-if="showAddForm"
      class="rounded-lg border border-primary-200 dark:border-primary-800 bg-primary-50 dark:bg-primary-950 p-4 mb-4"
    >
      <h3 class="text-sm font-semibold text-primary-700 dark:text-primary-300 mb-3">
        {{ $t('school.homeroom.add') }}
      </h3>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3 mb-3">
        <div>
          <label class="text-xs text-surface-500 mb-1 block">
            {{ $t('school.homeroom.homeroomTeacher') }} *
          </label>
          <InputNumber
            v-model="newForm.homeroomTeacherUserId"
            class="w-full"
            :placeholder="$t('school.homeroom.homeroomTeacher')"
            :use-grouping="false"
          />
        </div>
        <div>
          <label class="text-xs text-surface-500 mb-1 block">
            {{ $t('school.homeroom.academicYear') }}
          </label>
          <InputNumber
            v-model="newForm.academicYear"
            class="w-full"
            readonly
            :use-grouping="false"
          />
        </div>
        <div>
          <label class="text-xs text-surface-500 mb-1 block">
            {{ $t('school.homeroom.effectiveFrom') }} *
          </label>
          <InputText
            v-model="newForm.effectiveFrom"
            type="date"
            class="w-full"
          />
        </div>
        <div>
          <label class="text-xs text-surface-500 mb-1 block">
            {{ $t('school.homeroom.effectiveUntil') }}
          </label>
          <InputText
            v-model="newForm.effectiveUntil"
            type="date"
            class="w-full"
          />
        </div>
      </div>
      <div class="flex gap-2 justify-end">
        <Button
          :label="$t('common.cancel')"
          severity="secondary"
          size="small"
          @click="resetNewForm"
        />
        <Button
          :label="$t('school.homeroom.update')"
          :loading="submitting"
          :disabled="!newForm.homeroomTeacherUserId"
          size="small"
          @click="onAdd"
        />
      </div>
    </div>

    <!-- 読み込み中 -->
    <PageLoading v-if="loading" />

    <template v-else>
      <!-- データなし -->
      <div
        v-if="homerooms.length === 0"
        class="text-center text-surface-400 text-sm py-8 rounded-lg border border-surface-200 dark:border-surface-700"
      >
        {{ $t('school.homeroom.noHomerooms') }}
      </div>

      <!-- 担任設定一覧 -->
      <div v-else class="flex flex-col gap-3">
        <div
          v-for="homeroom in homerooms"
          :key="homeroom.id"
          class="rounded-lg border bg-surface-0 dark:bg-surface-900 p-4"
          :class="isCurrentlyActive(homeroom.effectiveFrom, homeroom.effectiveUntil)
            ? 'border-green-200 dark:border-green-800'
            : 'border-surface-200 dark:border-surface-700'"
        >
          <div class="flex items-start justify-between mb-2">
            <div class="flex items-center gap-2">
              <span
                v-if="isCurrentlyActive(homeroom.effectiveFrom, homeroom.effectiveUntil)"
                class="inline-block px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300"
              >
                {{ $t('school.homeroom.current') }}
              </span>
              <span class="text-sm font-semibold text-surface-800 dark:text-surface-100">
                {{ $t('school.homeroom.academicYear') }}: {{ homeroom.academicYear }}
              </span>
            </div>
            <Button
              :label="$t('common.edit')"
              size="small"
              severity="secondary"
              text
              @click="startEdit(homeroom.id, homeroom.effectiveUntil)"
            />
          </div>

          <div class="grid grid-cols-2 gap-2 text-sm mb-2">
            <div>
              <span class="text-surface-500 text-xs">{{ $t('school.homeroom.homeroomTeacher') }}</span>
              <div class="font-medium text-surface-800 dark:text-surface-100">
                ID: {{ homeroom.homeroomTeacherUserId }}
              </div>
            </div>
            <div v-if="homeroom.assistantTeacherUserIds.length > 0">
              <span class="text-surface-500 text-xs">{{ $t('school.homeroom.assistantTeachers') }}</span>
              <div class="text-surface-700 dark:text-surface-300">
                {{ homeroom.assistantTeacherUserIds.join(', ') }}
              </div>
            </div>
          </div>

          <div class="flex gap-4 text-xs text-surface-500">
            <span>{{ $t('school.homeroom.effectiveFrom') }}: {{ homeroom.effectiveFrom }}</span>
            <span v-if="homeroom.effectiveUntil">
              {{ $t('school.homeroom.effectiveUntil') }}: {{ homeroom.effectiveUntil }}
            </span>
          </div>

          <!-- 編集フォーム -->
          <div
            v-if="editingId === homeroom.id"
            class="mt-3 pt-3 border-t border-surface-200 dark:border-surface-700"
          >
            <div class="mb-2">
              <label class="text-xs text-surface-500 mb-1 block">
                {{ $t('school.homeroom.effectiveUntil') }}
              </label>
              <InputText
                v-model="editForm.effectiveUntil"
                type="date"
                class="w-full"
              />
            </div>
            <div class="flex gap-2 justify-end">
              <Button
                :label="$t('common.cancel')"
                severity="secondary"
                size="small"
                @click="cancelEdit"
              />
              <Button
                :label="$t('school.homeroom.update')"
                :loading="submitting"
                size="small"
                @click="onUpdate(homeroom.id)"
              />
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
