import DatePicker from 'primevue/datepicker'
import { applyDatePickerManualInputFix } from '~/utils/primevueDatePickerManualInput'

/**
 * PrimeVue DatePicker の手入力欠陥（CMP-260910-1557）を全画面に一括で修正する。
 * 詳細は `~/utils/primevueDatePickerManualInput` のヘッダコメントを参照。
 *
 * コンポーネント定義そのもの（`primevue/datepicker` のモジュール単一実体）に
 * パッチを当てるため、PrimeVue 自動インポート経由の `<DatePicker>` を含む
 * すべての利用箇所に効く。
 */
export default defineNuxtPlugin(() => {
  applyDatePickerManualInputFix(DatePicker)
})
