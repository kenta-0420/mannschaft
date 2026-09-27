export function useScopeLabels() {
  const { t } = useI18n()

  const templateLabel: Record<string, string> = {
    get CLUB() {
      return t('scopeLabels.template.CLUB')
    },
    get CLINIC() {
      return t('scopeLabels.template.CLINIC')
    },
    get CLASS() {
      return t('scopeLabels.template.CLASS')
    },
    get COMMUNITY() {
      return t('scopeLabels.template.COMMUNITY')
    },
    get COMPANY() {
      return t('scopeLabels.template.COMPANY')
    },
    get FAMILY() {
      return t('scopeLabels.template.FAMILY')
    },
    get RESTAURANT() {
      return t('scopeLabels.template.RESTAURANT')
    },
    get BEAUTY() {
      return t('scopeLabels.template.BEAUTY')
    },
    get STORE() {
      return t('scopeLabels.template.STORE')
    },
    get VOLUNTEER() {
      return t('scopeLabels.template.VOLUNTEER')
    },
    get NEIGHBORHOOD() {
      return t('scopeLabels.template.NEIGHBORHOOD')
    },
    get CONDO() {
      return t('scopeLabels.template.CONDO')
    },
    get OTHER() {
      return t('scopeLabels.template.OTHER')
    },
  }

  const orgTypeLabel: Record<string, string> = {
    get GOVERNMENT() {
      return t('scopeLabels.organizationType.GOVERNMENT')
    },
    get MUNICIPALITY() {
      return t('scopeLabels.organizationType.MUNICIPALITY')
    },
    get COMPANY() {
      return t('scopeLabels.organizationType.COMPANY')
    },
    get HOSPITAL() {
      return t('scopeLabels.organizationType.HOSPITAL')
    },
    get ASSOCIATION() {
      return t('scopeLabels.organizationType.ASSOCIATION')
    },
    get SCHOOL() {
      return t('scopeLabels.organizationType.SCHOOL')
    },
    get NPO() {
      return t('scopeLabels.organizationType.NPO')
    },
    get COMMUNITY() {
      return t('scopeLabels.organizationType.COMMUNITY')
    },
    get OTHER() {
      return t('scopeLabels.organizationType.OTHER')
    },
  }

  const visibilityLabel: Record<string, string> = {
    get PUBLIC() {
      return t('scopeLabels.visibility.PUBLIC')
    },
    get GUESTS_AND_ABOVE() {
      return t('scopeLabels.visibility.GUESTS_AND_ABOVE')
    },
    get SUPPORTERS_AND_ABOVE() {
      return t('scopeLabels.visibility.SUPPORTERS_AND_ABOVE')
    },
    get MEMBERS_AND_ABOVE() {
      return t('scopeLabels.visibility.MEMBERS_AND_ABOVE')
    },
  }

  return { templateLabel, orgTypeLabel, visibilityLabel }
}
