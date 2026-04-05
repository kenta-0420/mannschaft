import type { MemberCard } from '~/types/member-card'

export function useAccountMemberCards() {
  const notification = useNotification()
  const memberCardApi = useMemberCardApi()

  const memberCards = ref<MemberCard[]>([])
  const selectedCard = ref<MemberCard | null>(null)
  const memberCardActiveTab = ref('0')

  async function loadMemberCards() {
    try {
      memberCards.value = await memberCardApi.listMy()
    } catch {
      /* silent */
    }
  }

  async function handleSuspendCard(id: number) {
    try {
      await memberCardApi.suspend(id)
      notification.success('会員証を一時停止しました')
      await loadMemberCards()
    } catch {
      notification.error('一時停止に失敗しました')
    }
  }

  async function handleReactivateCard(id: number) {
    try {
      await memberCardApi.reactivate(id)
      notification.success('会員証を再開しました')
      await loadMemberCards()
    } catch {
      notification.error('再開に失敗しました')
    }
  }

  function handleSelectCard(card: MemberCard) {
    selectedCard.value = card
    memberCardActiveTab.value = '1'
  }

  return {
    memberCards,
    selectedCard,
    memberCardActiveTab,
    loadMemberCards,
    handleSuspendCard,
    handleReactivateCard,
    handleSelectCard,
  }
}
