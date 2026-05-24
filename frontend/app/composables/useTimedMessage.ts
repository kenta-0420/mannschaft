import dayjs from 'dayjs'

export function useTimedMessage() {
  const { tm, rt } = useI18n()
  const { userTimezone } = useDatetime()
  const message = ref('')

  function pick() {
    const hour = dayjs().tz(userTimezone.value).hour()
    let period: string
    if (hour >= 5 && hour < 9) period = 'earlyMorning'
    else if (hour >= 9 && hour < 12) period = 'morning'
    else if (hour >= 12 && hour < 17) period = 'afternoon'
    else if (hour >= 17 && hour < 21) period = 'evening'
    else period = 'night'

    // tm() は配列要素を compiled message AST として返すため、
    // .value プロパティでは取り出せず undefined になる（commit 0554b54d4 の typecheck 対応で誤って導入されたリグレッション）。
    // rt() は compiled message を文字列に解決する公式 API のため、各要素に対して rt() を適用する。
    const raw: unknown = tm(`timedMessage.${period}`)
    if (Array.isArray(raw) && raw.length > 0) {
      const picked = raw[Math.floor(Math.random() * raw.length)]
      // as Parameters<typeof rt>[0] で型穴を埋める（compiled message AST の型を vue-i18n が露出していないため）
      message.value = rt(picked as Parameters<typeof rt>[0])
    }
  }

  onMounted(pick)

  return message
}
