import type { DatePickerPassThroughOptions } from 'primevue/datepicker'

/** 会計年度フォーム専用。7列の日付も360px幅に収めつつ操作領域を44px以上確保する。 */
export const fiscalYearDatePickerPt: DatePickerPassThroughOptions = {
  dropdown: { class: 'min-h-11 min-w-11' },
  panel: { class: '!p-2' },
  pcPrevButton: { root: { class: 'min-h-11 min-w-11' } },
  pcNextButton: { root: { class: 'min-h-11 min-w-11' } },
  selectMonth: { class: 'min-h-11 min-w-11' },
  selectYear: { class: 'min-h-11 min-w-11' },
  dayCell: { class: '!p-0' },
  day: { class: 'min-h-11 min-w-11' },
  month: { class: 'min-h-11 min-w-11' },
  year: { class: 'min-h-11 min-w-11' },
}
