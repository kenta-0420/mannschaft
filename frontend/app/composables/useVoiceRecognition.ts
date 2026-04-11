/**
 * Web Speech API ラッパー。
 * Chrome / Edge / Safari 対応。Firefox は isSupported=false になりボタンが非表示になる。
 */
export function useVoiceRecognition() {
  const isSupported =
    typeof window !== 'undefined' &&
    ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window)

  const isListening = ref(false)
  const transcript = ref('')

  let recognition: InstanceType<typeof SpeechRecognition> | null = null

  function start(lang = 'ja-JP') {
    if (!isSupported) return
    const SpeechRecognitionClass =
      (window as Window & { SpeechRecognition?: typeof SpeechRecognition; webkitSpeechRecognition?: typeof SpeechRecognition }).SpeechRecognition ||
      (window as Window & { webkitSpeechRecognition?: typeof SpeechRecognition }).webkitSpeechRecognition
    if (!SpeechRecognitionClass) return

    recognition = new SpeechRecognitionClass()
    recognition.lang = lang
    recognition.continuous = true
    recognition.interimResults = true

    recognition.onresult = (event: SpeechRecognitionEvent) => {
      let interim = ''
      let final = ''
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i]
        if (result && result.isFinal) {
          final += result[0]?.transcript ?? ''
        } else {
          interim += result?.[0]?.transcript ?? ''
        }
      }
      if (final) transcript.value += final
    }

    recognition.onerror = () => {
      isListening.value = false
    }

    recognition.onend = () => {
      isListening.value = false
    }

    recognition.start()
    isListening.value = true
  }

  function stop() {
    recognition?.stop()
    isListening.value = false
  }

  function reset() {
    transcript.value = ''
  }

  return { isSupported, isListening, transcript, start, stop, reset }
}
