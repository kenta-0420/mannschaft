export function useGreeting() {
  const greeting = ref('')

  function update() {
    const hour = new Date().getHours()
    if (hour >= 5 && hour < 12) {
      greeting.value = 'おはようございます'
    }
    else if (hour >= 12 && hour < 18) {
      greeting.value = 'こんにちは'
    }
    else {
      greeting.value = 'こんばんは'
    }
  }

  onMounted(update)

  return greeting
}
