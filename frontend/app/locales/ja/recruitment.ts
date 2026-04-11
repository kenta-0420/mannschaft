export default {
  "recruitment": {
    "category": {
      "futsal_open": "フットサル個人参加",
      "soccer_open": "サッカー個人参加",
      "basketball_open": "バスケットボール個人参加",
      "yoga_class": "ヨガクラス",
      "swimming_class": "スイミングクラス",
      "dance_class": "ダンスクラス",
      "fitness_class": "フィットネス・トレーニング",
      "match_opponent": "対戦相手募集",
      "practice_match": "練習試合相手募集",
      "tournament": "大会・トーナメント",
      "referee": "審判募集",
      "staff": "スタッフ・コーチ募集",
      "event_meeting": "イベント・交流会",
      "workshop": "ワークショップ・体験会",
      "other": "その他"
    },
    "status": {
      "draft": "下書き",
      "open": "募集中",
      "full": "満員",
      "closed": "締切後",
      "cancelled": "キャンセル"
    },
    "participantStatus": {
      "applied": "申込済み",
      "confirmed": "確定",
      "waitlisted": "キャンセル待ち",
      "cancelled": "キャンセル",
      "attended": "出席"
    },
    "participationType": {
      "individual": "個人参加",
      "team": "チーム参加"
    },
    "visibility": {
      "public": "全体公開",
      "scopeOnly": "スコープ内のみ",
      "supportersOnly": "サポーターのみ"
    },
    "field": {
      "title": "タイトル",
      "description": "詳細説明",
      "category": "カテゴリ",
      "subcategory": "サブカテゴリ",
      "startAt": "開催開始",
      "endAt": "開催終了",
      "applicationDeadline": "応募締切",
      "autoCancelAt": "自動キャンセル判定時刻",
      "capacity": "定員",
      "minCapacity": "最小定員",
      "location": "開催場所",
      "price": "料金",
      "paymentEnabled": "決済を有効化",
      "cancellationPolicy": "キャンセルポリシー",
      "imageUrl": "画像URL",
      "note": "メモ",
      "participationType": "参加形式",
      "visibility": "公開範囲"
    },
    "action": {
      "create": "作成",
      "publish": "公開",
      "edit": "編集",
      "save": "保存",
      "cancel": "キャンセル",
      "archive": "削除",
      "apply": "申込",
      "cancelMyApplication": "申込をキャンセル",
      "joinWaitlist": "キャンセル待ちに登録",
      "viewDetails": "詳細を見る",
      "createPolicy": "ポリシー作成"
    },
    "confirmModal": {
      "cancellationFee": {
        "title": "キャンセル料の確認",
        "message": "このキャンセルにはキャンセル料が発生します。よろしいですか?",
        "feeAmountLabel": "キャンセル料",
        "freeMessage": "現在は無料でキャンセルできます。",
        "agreeButton": "同意してキャンセル",
        "disagreeButton": "戻る"
      },
      "publish": {
        "title": "募集を公開",
        "message": "この募集を公開しますか?公開後は配信スコープに通知されます。"
      },
      "cancel": {
        "title": "募集をキャンセル",
        "message": "この募集をキャンセルしますか?参加者全員に通知されます。"
      }
    },
    "policy": {
      "freeUntilHoursBefore": "無料境界 (開催の何時間前まで無料か)",
      "freeUntilHoursBeforeHelp": "例: 168 = 7日前まで無料",
      "addTier": "段階を追加",
      "removeTier": "段階を削除",
      "tierOrder": "段階",
      "noPolicyMessage": "ポリシーが設定されていません"
    },
    "tier": {
      "percentage": "割合 (%)",
      "fixed": "固定金額 (円)",
      "appliesAtOrBeforeHours": "適用境界 (開催のN時間前以内)",
      "feeValue": "料金値",
      "feeType": "料金タイプ"
    },
    "nav": {
      "recruitmentListings": "募集一覧",
      "myRecruitmentListings": "自分の参加",
      "createListing": "募集を作成",
      "cancellationPolicies": "キャンセルポリシー"
    },
    "page": {
      "myRecruitmentListings": "自分の参加予定",
      "teamRecruitmentListings": "募集枠管理",
      "newListing": "募集枠を作成",
      "editListing": "募集枠を編集",
      "listingDetail": "募集詳細",
      "cancellationPolicies": "キャンセルポリシー管理"
    },
    "label": {
      "participants": "参加者",
      "remainingCapacity": "残り枠",
      "waitlistCount": "キャンセル待ち",
      "noListings": "募集はありません",
      "noParticipants": "参加者はいません",
      "myApplication": "あなたの申込",
      "freeOfCharge": "無料"
    },
    "search": {
      "pageTitle": "募集を探す",
      "keyword": "キーワード",
      "keywordPlaceholder": "タイトル・説明で検索",
      "location": "開催場所",
      "locationPlaceholder": "場所で絞り込み",
      "participationType": "参加形式",
      "allTypes": "すべての形式",
      "startFrom": "開催日（From）",
      "startTo": "開催日（To）",
      "searchButton": "検索",
      "resetButton": "リセット",
      "allCategories": "すべてのカテゴリ",
      "noResults": "条件に合う募集が見つかりませんでした",
      "resultsCount": "{count}件の募集",
      "capacity": "定員",
      "remaining": "残り{count}枠",
      "free": "無料",
      "priceLabel": "{price}円",
      "applying": "申込中",
      "individual": "個人",
      "team": "チーム"
    },
    "error": {
      "RECRUITMENT_001": "募集が見つかりません",
      "RECRUITMENT_002": "募集の作成権限がありません",
      "RECRUITMENT_003": "公開範囲によりこの募集を閲覧できません",
      "RECRUITMENT_005": "定員に達しています",
      "RECRUITMENT_007": "参加形式が一致しません",
      "RECRUITMENT_008": "最小定員が定員を超えています",
      "RECRUITMENT_009": "予約ラインまたは募集と時間が衝突します",
      "RECRUITMENT_012": "カテゴリが指定されていません",
      "RECRUITMENT_015": "決済を有効化する場合は料金の指定が必要です",
      "RECRUITMENT_020": "下書き募集の閲覧権限がありません",
      "RECRUITMENT_100": "不正な状態遷移です",
      "RECRUITMENT_101": "応募締切を過ぎています",
      "RECRUITMENT_102": "既にキャンセル済みです",
      "RECRUITMENT_103": "下書きのままでは申込できません",
      "RECRUITMENT_104": "開催完了済みの編集はできません",
      "RECRUITMENT_105": "既に申込済みです",
      "RECRUITMENT_106": "キャンセル待ち上限に達しています",
      "RECRUITMENT_204": "配信対象が0件のため公開できません",
      "RECRUITMENT_205": "画像URLが許可リストに含まれていません",
      "RECRUITMENT_206": "定員を確定参加者数より少なく変更できません",
      "RECRUITMENT_207": "公開範囲と配信対象の組合せが不正です",
      "RECRUITMENT_301": "キャンセル料の決済に失敗しました",
      "RECRUITMENT_302": "キャンセルポリシーの設定が不正です",
      "RECRUITMENT_303": "キャンセルポリシーの段階が4を超えています",
      "RECRUITMENT_304": "キャンセル料の確認が必要です",
      "RECRUITMENT_307": "キャンセルポリシー段階の時間範囲が重複しています",
      "RECRUITMENT_308": "表示されたキャンセル料と実際の料金が異なります。再試算してください"
    }
  }
}
