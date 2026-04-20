export function useScopeLabels() {
  const templateLabel: Record<string, string> = {
    CLUB: 'クラブ・サークル',
    CLINIC: 'クリニック',
    CLASS: 'クラス',
    COMMUNITY: 'コミュニティ',
    COMPANY: '企業',
    FAMILY: '家族',
    RESTAURANT: '飲食店',
    BEAUTY: '美容院・サロン',
    STORE: '店舗・小売',
    VOLUNTEER: 'ボランティア・NPO',
    NEIGHBORHOOD: '自治会',
    CONDO: 'マンション管理組合',
    OTHER: 'その他',
  }

  const orgTypeLabel: Record<string, string> = {
    GOVERNMENT: '行政・官公庁',
    MUNICIPALITY: '自治体（市区町村）',
    COMPANY: '会社・企業',
    HOSPITAL: '病院・医療機関',
    ASSOCIATION: '協会・連盟',
    SCHOOL: '学校・教育機関',
    NPO: 'NPO・非営利団体',
    COMMUNITY: 'コミュニティ',
    OTHER: 'その他',
  }

  const visibilityLabel: Record<string, string> = {
    PUBLIC: '公開',
    ORGANIZATION_ONLY: 'チーム内のみ',
    PRIVATE: '非公開',
  }

  return { templateLabel, orgTypeLabel, visibilityLabel }
}
