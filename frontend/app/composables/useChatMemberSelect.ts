import type { MemberResponse } from '~/types/member'

export function useChatMemberSelect(
  allMembers: Ref<MemberResponse[]>,
  currentUserId: Ref<number | undefined>,
) {
  const selected = ref<MemberResponse[]>([])
  const search = ref('')

  const filtered = computed(() => {
    const me = currentUserId.value
    const q = search.value.toLowerCase()
    return allMembers.value
      .filter((m) => m.userId !== me)
      .filter((m) => !q || m.displayName.toLowerCase().includes(q))
  })

  function toggle(m: MemberResponse) {
    const idx = selected.value.findIndex((s) => s.userId === m.userId)
    if (idx >= 0) {
      selected.value.splice(idx, 1)
    } else {
      selected.value.push(m)
    }
  }

  function isSelected(m: MemberResponse): boolean {
    return selected.value.some((s) => s.userId === m.userId)
  }

  function reset() {
    selected.value = []
    search.value = ''
  }

  return { selected, search, filtered, toggle, isSelected, reset }
}
