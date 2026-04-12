export default {
  "recruitment": {
    "category": {
      "futsal_open": "五人制足球个人参加",
      "soccer_open": "足球个人参加",
      "basketball_open": "篮球个人参加",
      "yoga_class": "瑜伽课",
      "swimming_class": "游泳课",
      "dance_class": "舞蹈课",
      "fitness_class": "健身训练",
      "match_opponent": "招募对战对手",
      "practice_match": "招募练习赛对手",
      "tournament": "比赛・锦标赛",
      "referee": "招募裁判",
      "staff": "招募工作人员・教练",
      "event_meeting": "活动・交流会",
      "workshop": "工作坊・体验会",
      "other": "其他"
    },
    "status": {
      "draft": "草稿",
      "open": "招募中",
      "full": "已满",
      "closed": "已截止",
      "cancelled": "已取消"
    },
    "participantStatus": {
      "applied": "已申请",
      "confirmed": "已确认",
      "waitlisted": "候补中",
      "cancelled": "已取消",
      "attended": "已出席"
    },
    "participationType": {
      "individual": "个人参加",
      "team": "团队参加"
    },
    "visibility": {
      "public": "公开",
      "scopeOnly": "仅范围内",
      "supportersOnly": "仅支持者"
    },
    "field": {
      "title": "标题",
      "description": "详细说明",
      "category": "类别",
      "subcategory": "子类别",
      "startAt": "开始时间",
      "endAt": "结束时间",
      "applicationDeadline": "申请截止时间",
      "autoCancelAt": "自动取消判定时间",
      "capacity": "名额",
      "minCapacity": "最小名额",
      "location": "举办地点",
      "price": "费用",
      "paymentEnabled": "启用支付",
      "cancellationPolicy": "取消政策",
      "imageUrl": "图片URL",
      "note": "备注",
      "participationType": "参加形式",
      "visibility": "公开范围"
    },
    "action": {
      "create": "创建",
      "publish": "发布",
      "edit": "编辑",
      "save": "保存",
      "cancel": "取消",
      "archive": "删除",
      "apply": "申请",
      "cancelMyApplication": "取消我的申请",
      "joinWaitlist": "加入候补",
      "viewDetails": "查看详情",
      "createPolicy": "创建政策"
    },
    "confirmModal": {
      "cancellationFee": {
        "title": "确认取消费用",
        "message": "此次取消将产生费用。确定要继续吗?",
        "feeAmountLabel": "取消费用",
        "freeMessage": "目前可以免费取消。",
        "agreeButton": "同意并取消",
        "disagreeButton": "返回"
      },
      "publish": {
        "title": "发布招募",
        "message": "要发布此招募吗?发布后将通知配送范围。"
      },
      "cancel": {
        "title": "取消招募",
        "message": "要取消此招募吗?将通知所有参加者。"
      }
    },
    "policy": {
      "freeUntilHoursBefore": "免费边界 (开始前N小时之前免费)",
      "freeUntilHoursBeforeHelp": "示例: 168 = 7天前免费",
      "addTier": "添加阶段",
      "removeTier": "删除阶段",
      "tierOrder": "阶段",
      "noPolicyMessage": "未设置政策"
    },
    "tier": {
      "percentage": "百分比 (%)",
      "fixed": "固定金额 (日元)",
      "appliesAtOrBeforeHours": "适用边界 (开始前N小时以内)",
      "feeValue": "费用值",
      "feeType": "费用类型"
    },
    "nav": {
      "recruitmentListings": "招募列表",
      "myRecruitmentListings": "我的参加",
      "createListing": "创建招募",
      "cancellationPolicies": "取消政策"
    },
    "page": {
      "myRecruitmentListings": "我的参加预定",
      "teamRecruitmentListings": "招募管理",
      "newListing": "创建招募",
      "editListing": "编辑招募",
      "listingDetail": "招募详情",
      "cancellationPolicies": "取消政策管理"
    },
    "label": {
      "participants": "参加者",
      "remainingCapacity": "剩余名额",
      "waitlistCount": "候补人数",
      "noListings": "暂无招募",
      "noParticipants": "暂无参加者",
      "myApplication": "您的申请",
      "freeOfCharge": "免费"
    },
    "search": {
      "pageTitle": "查找招募",
      "keyword": "关键词",
      "keywordPlaceholder": "按标题或描述搜索",
      "location": "地点",
      "locationPlaceholder": "按地点筛选",
      "participationType": "参与形式",
      "allTypes": "所有形式",
      "startFrom": "开始日期（从）",
      "startTo": "开始日期（至）",
      "searchButton": "搜索",
      "resetButton": "重置",
      "allCategories": "所有类别",
      "noResults": "没有找到符合条件的招募",
      "resultsCount": "{count}条招募",
      "capacity": "定员",
      "remaining": "剩余{count}名额",
      "free": "免费",
      "priceLabel": "¥{price}",
      "applying": "申请中",
      "individual": "个人",
      "team": "团队"
    },
    "error": {
      "RECRUITMENT_001": "未找到招募",
      "RECRUITMENT_002": "没有创建招募的权限",
      "RECRUITMENT_003": "因公开范围限制无法查看此招募",
      "RECRUITMENT_005": "名额已满",
      "RECRUITMENT_007": "参加形式不匹配",
      "RECRUITMENT_008": "最小名额超过总名额",
      "RECRUITMENT_009": "时间与预约线或其他招募冲突",
      "RECRUITMENT_012": "未指定类别",
      "RECRUITMENT_015": "启用支付时必须指定费用",
      "RECRUITMENT_020": "没有查看草稿招募的权限",
      "RECRUITMENT_100": "状态转换不合法",
      "RECRUITMENT_101": "已超过申请截止时间",
      "RECRUITMENT_102": "已经取消",
      "RECRUITMENT_103": "草稿状态下无法申请",
      "RECRUITMENT_104": "已结束的招募无法编辑",
      "RECRUITMENT_105": "已经申请过",
      "RECRUITMENT_106": "候补名单已满",
      "RECRUITMENT_204": "配送对象为0无法发布",
      "RECRUITMENT_205": "图片URL不在白名单中",
      "RECRUITMENT_206": "无法将名额减少到低于已确认参加者数",
      "RECRUITMENT_207": "公开范围与配送对象不一致",
      "RECRUITMENT_301": "取消费用支付失败",
      "RECRUITMENT_302": "取消政策设置不正确",
      "RECRUITMENT_303": "取消政策阶段超过4个",
      "RECRUITMENT_304": "需要确认取消费用",
      "RECRUITMENT_307": "取消政策阶段时间范围重叠",
      "RECRUITMENT_308": "显示的取消费用与实际不符。请重新计算",
      "RECRUITMENT_305": "异议申请期限已过",
      "RECRUITMENT_309": "未找到NO_SHOW记录",
      "RECRUITMENT_310": "未找到处罚",
      "RECRUITMENT_311": "已提交异议",
      "RECRUITMENT_312": "未找到处罚设置"
    },
    "noShow": {
      "pageTitle": "NO_SHOW履歴",
      "adminPageTitle": "NO_SHOW管理",
      "status": {
        "pending": "确认待ち",
        "confirmed": "确定",
        "disputed": "异议中",
        "revoked": "撤销",
        "upheld": "维持"
      },
      "reason": {
        "adminMarked": "管理员记录",
        "autoDetected": "自动检测"
      },
      "disputeButton": "提出异议",
      "disputeDialog": {
        "title": "提出异议",
        "reasonLabel": "理由",
        "reasonPlaceholder": "请输入具体理由",
        "submit": "提交"
      },
      "resolveDialog": {
        "title": "解决异议",
        "revoke": "撤销（REVOKED）",
        "uphold": "维持（UPHELD）",
        "adminNote": "管理员备注",
        "submit": "解决"
      }
    },
    "penalty": {
      "pageTitle": "处罚设置",
      "penaltiesPageTitle": "处罚列表",
      "enabled": "有效",
      "disabled": "无效",
      "thresholdCount": "阈值次数",
      "thresholdPeriodDays": "统计周期（天）",
      "penaltyDurationDays": "处罚期间（天）",
      "autoDetection": "自动检测",
      "disputeAllowedDays": "异议期限（天）",
      "liftButton": "解除处罚",
      "saveButton": "保存设置",
      "liftDialog": {
        "title": "解除处罚",
        "adminManual": "管理员手动解除"
      },
      "status": {
        "active": "有效中",
        "expired": "已过期",
        "lifted": "已解除"
      }
    }
  }
}
