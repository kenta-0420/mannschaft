import type { MemberCard } from '~/types/member-card'

export function useAccountMemberCards() {
  const notification = useNotification()
  const { t } = useI18n()
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
      notification.success(t('settings.member_card.toast.suspend_success'))
      await loadMemberCards()
    } catch {
      notification.error(t('settings.member_card.toast.suspend_error'))
    }
  }

  async function handleReactivateCard(id: number) {
    try {
      await memberCardApi.reactivate(id)
      notification.success(t('settings.member_card.toast.reactivate_success'))
      await loadMemberCards()
    } catch {
      notification.error(t('settings.member_card.toast.reactivate_error'))
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
