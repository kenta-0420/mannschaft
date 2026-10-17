import { z } from 'zod'
import { toLocalDateString } from '~/utils/localDate'

/** 画面とAPIの会計年度入力条件を揃える。 */
export function budgetFiscalYearSchema(t: (key: string) => string) {
  return z
    .object({
      name: z
        .string()
        .trim()
        .min(1, t('budget_accounting.validation.name_required'))
        .max(100, t('budget_accounting.validation.name_max')),
      startDate: z
        .date({
          required_error: t('budget_accounting.validation.date_required'),
          invalid_type_error: t('budget_accounting.validation.date_required'),
        })
        .nullable()
        .refine((value) => value !== null, t('budget_accounting.validation.date_required')),
      endDate: z
        .date({
          required_error: t('budget_accounting.validation.date_required'),
          invalid_type_error: t('budget_accounting.validation.date_required'),
        })
        .nullable()
        .refine((value) => value !== null, t('budget_accounting.validation.date_required')),
    })
    .superRefine((value, context) => {
      if (
        value.startDate &&
        value.endDate &&
        toLocalDateString(value.startDate) >= toLocalDateString(value.endDate)
      ) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['endDate'],
          message: t('budget_accounting.validation.date_order'),
        })
      }
    })
}

export type FiscalYearFormInput = z.input<ReturnType<typeof budgetFiscalYearSchema>>
export type FiscalYearForm = z.output<ReturnType<typeof budgetFiscalYearSchema>>
