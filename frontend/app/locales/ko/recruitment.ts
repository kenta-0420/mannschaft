export default {
  "recruitment": {
    "category": {
      "futsal_open": "풋살 개인 참가",
      "soccer_open": "축구 개인 참가",
      "basketball_open": "농구 개인 참가",
      "yoga_class": "요가 클래스",
      "swimming_class": "수영 클래스",
      "dance_class": "댄스 클래스",
      "fitness_class": "피트니스・트레이닝",
      "match_opponent": "대전 상대 모집",
      "practice_match": "연습 경기 상대 모집",
      "tournament": "대회・토너먼트",
      "referee": "심판 모집",
      "staff": "스태프・코치 모집",
      "event_meeting": "이벤트・교류회",
      "workshop": "워크숍・체험회",
      "other": "기타"
    },
    "status": {
      "draft": "초안",
      "open": "모집 중",
      "full": "정원 마감",
      "closed": "마감 후",
      "cancelled": "취소"
    },
    "participantStatus": {
      "applied": "신청 완료",
      "confirmed": "확정",
      "waitlisted": "대기 중",
      "cancelled": "취소",
      "attended": "참석"
    },
    "participationType": {
      "individual": "개인 참가",
      "team": "팀 참가"
    },
    "visibility": {
      "public": "전체 공개",
      "scopeOnly": "범위 내만",
      "supportersOnly": "서포터만"
    },
    "field": {
      "title": "제목",
      "description": "상세 설명",
      "category": "카테고리",
      "subcategory": "서브 카테고리",
      "startAt": "개최 시작",
      "endAt": "개최 종료",
      "applicationDeadline": "신청 마감",
      "autoCancelAt": "자동 취소 판정 시각",
      "capacity": "정원",
      "minCapacity": "최소 정원",
      "location": "개최 장소",
      "price": "요금",
      "paymentEnabled": "결제 활성화",
      "cancellationPolicy": "취소 정책",
      "imageUrl": "이미지 URL",
      "note": "메모",
      "participationType": "참가 형식",
      "visibility": "공개 범위"
    },
    "action": {
      "create": "작성",
      "publish": "공개",
      "edit": "편집",
      "save": "저장",
      "cancel": "취소",
      "archive": "삭제",
      "apply": "신청",
      "cancelMyApplication": "내 신청 취소",
      "joinWaitlist": "대기열에 등록",
      "viewDetails": "자세히 보기",
      "createPolicy": "정책 작성"
    },
    "confirmModal": {
      "cancellationFee": {
        "title": "취소 수수료 확인",
        "message": "이 취소에는 취소 수수료가 발생합니다. 진행하시겠습니까?",
        "feeAmountLabel": "취소 수수료",
        "freeMessage": "현재는 무료로 취소할 수 있습니다.",
        "agreeButton": "동의하고 취소",
        "disagreeButton": "돌아가기"
      },
      "publish": {
        "title": "모집 공개",
        "message": "이 모집을 공개하시겠습니까? 공개 후 배포 범위에 알림이 갑니다."
      },
      "cancel": {
        "title": "모집 취소",
        "message": "이 모집을 취소하시겠습니까? 모든 참가자에게 알림이 갑니다."
      }
    },
    "policy": {
      "freeUntilHoursBefore": "무료 경계 (개최 N시간 전까지 무료)",
      "freeUntilHoursBeforeHelp": "예: 168 = 7일 전까지 무료",
      "addTier": "단계 추가",
      "removeTier": "단계 삭제",
      "tierOrder": "단계",
      "noPolicyMessage": "정책이 설정되지 않았습니다"
    },
    "tier": {
      "percentage": "비율 (%)",
      "fixed": "고정 금액 (엔)",
      "appliesAtOrBeforeHours": "적용 경계 (개최 N시간 이내)",
      "feeValue": "수수료 값",
      "feeType": "수수료 유형"
    },
    "nav": {
      "recruitmentListings": "모집 목록",
      "myRecruitmentListings": "내 참가",
      "createListing": "모집 작성",
      "cancellationPolicies": "취소 정책"
    },
    "page": {
      "myRecruitmentListings": "내 참가 예정",
      "teamRecruitmentListings": "모집 관리",
      "newListing": "모집 작성",
      "editListing": "모집 편집",
      "listingDetail": "모집 상세",
      "cancellationPolicies": "취소 정책 관리"
    },
    "label": {
      "participants": "참가자",
      "remainingCapacity": "남은 자리",
      "waitlistCount": "대기 인원",
      "noListings": "모집이 없습니다",
      "noParticipants": "참가자가 없습니다",
      "myApplication": "내 신청",
      "freeOfCharge": "무료"
    },
    "error": {
      "RECRUITMENT_001": "모집을 찾을 수 없습니다",
      "RECRUITMENT_002": "모집 작성 권한이 없습니다",
      "RECRUITMENT_003": "공개 범위로 인해 이 모집을 볼 수 없습니다",
      "RECRUITMENT_005": "정원에 도달했습니다",
      "RECRUITMENT_007": "참가 형식이 일치하지 않습니다",
      "RECRUITMENT_008": "최소 정원이 정원을 초과합니다",
      "RECRUITMENT_009": "예약 라인 또는 다른 모집과 시간이 충돌합니다",
      "RECRUITMENT_012": "카테고리가 지정되지 않았습니다",
      "RECRUITMENT_015": "결제를 활성화할 경우 요금 지정이 필요합니다",
      "RECRUITMENT_020": "초안 모집의 열람 권한이 없습니다",
      "RECRUITMENT_100": "잘못된 상태 전환입니다",
      "RECRUITMENT_101": "신청 마감을 지났습니다",
      "RECRUITMENT_102": "이미 취소되었습니다",
      "RECRUITMENT_103": "초안 상태에서는 신청할 수 없습니다",
      "RECRUITMENT_104": "종료된 모집은 편집할 수 없습니다",
      "RECRUITMENT_105": "이미 신청했습니다",
      "RECRUITMENT_106": "대기열 한도를 초과했습니다",
      "RECRUITMENT_204": "배포 대상이 0건이므로 공개할 수 없습니다",
      "RECRUITMENT_205": "이미지 URL이 화이트리스트에 없습니다",
      "RECRUITMENT_206": "정원을 확정 참가자 수보다 적게 변경할 수 없습니다",
      "RECRUITMENT_207": "공개 범위와 배포 대상이 일치하지 않습니다",
      "RECRUITMENT_301": "취소 수수료 결제에 실패했습니다",
      "RECRUITMENT_302": "취소 정책 설정이 잘못되었습니다",
      "RECRUITMENT_303": "취소 정책 단계가 4개를 초과합니다",
      "RECRUITMENT_304": "취소 수수료 확인이 필요합니다",
      "RECRUITMENT_307": "취소 정책 단계의 시간 범위가 중복됩니다",
      "RECRUITMENT_308": "표시된 취소 수수료가 실제와 다릅니다. 다시 계산하세요"
    }
  }
}
