export function useTimedMessage() {
  const { tm, rt } = useI18n()
  const message = ref('')

  function pick() {
    const hour = new Date().getHours()
    let period: string
    if (hour >= 5 && hour < 9) period = 'earlyMorning'
    else if (hour >= 9 && hour < 12) period = 'morning'
    else if (hour >= 12 && hour < 17) period = 'afternoon'
    else if (hour >= 17 && hour < 21) period = 'evening'
    else period = 'night'

    const raw = tm(`timedMessage.${period}`)
    const messages = Array.isArray(raw) ? raw.map((m) => rt(m)) : []
    if (messages.length > 0) {
      message.value = messages[Math.floor(Math.random() * messages.length)]
    }
  }

  onMounted(pick)

  return message
}
