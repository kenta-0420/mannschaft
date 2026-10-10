# 削除済みチームに残る予定の掃除（CMP-260902-0059）

既設の SYSTEM_ADMIN 専用復元 API と予定削除 API を使う。チームは論理削除を維持し、クロスドメイン CASCADE や一括 DELETE は使わない。既存のテスト利用者の孤児データをこの変更の検証で操作しない。

## 対象の確定

認可された運用担当者が読み取り専用の接続で対象を調査し、チームの現在の slug、全期間の未削除予定 ID、繰り返し親子関係、元のチーム管理者を記録する。月単位のカレンダー表示だけでは全期間の予定・繰り返し親を取りこぼす。対象チームの ID を確定してから、以下の SELECT の `:teamId` にその ID を束縛する。

```sql
SELECT id, slug, lifecycle_status, visibility, archived_at, deleted_at
FROM teams WHERE id = :teamId;

SELECT id, parent_schedule_id, recurrence_rule, start_at, end_at, status
FROM schedules WHERE team_id = :teamId AND deleted_at IS NULL
ORDER BY start_at, id;
```

予定 ID とチーム ID の対応を確認して削除対象を確定する。API の一覧は期間指定が必須で、可視性判定も入るため、対象確定の SELECT と照合する。実際の認証情報を記録・共有しない。

## 復元と掃除

1. SYSTEM_ADMIN が `PATCH /api/v1/teams/{slug}/restore` を実行し、204 を確認する。不存在は404、既に未削除なら409（`TEAM_006`）であり、後者は現状態を読み取り確認してから続行する。
2. 復元は `deleted_at` だけを NULL に戻す。元の公開範囲と保持されたロールが再び有効になるため、運用担当者はこの影響を踏まえて作業する。色設定・キープ・フォルダー項目等の削除済み関連状態は復元しない。PROVISIONED は復元後も通常 API の ACTIVE 限定ゲートにより非公開のままで、本手順の予定掃除は通常 API に到達できる ACTIVE チームを対象とする。
3. 繰り返し親がある場合、対象 inventory を確定したうえで親を `DELETE /api/v1/teams/{slug}/schedules/{id}?updateScope=THIS_ONLY` で先に論理削除し、親自体も未削除行として残さない。既に生成された子は別行として残る。親 ID に `updateScope=ALL` を付けても必ず子が消えるわけではない。既存実装の ALL 分岐は `parentScheduleId != null` の子から呼んだ場合に親・全子を削除する。
4. SYSTEM_ADMIN が inventory に残るすべての子・単発予定 ID を同じ予定削除 API の `THIS_ONLY` で削除し、ID ごとの204と進捗を記録する。期間を限定せず、キャンセル済みの未削除行も確認する。子起点の ALL を使う場合は、そのシリーズ全件が対象であることを先に確認する。
5. 読み取り SELECT を再実行し、対象チームの未削除予定が0件であることを確認する。新たな行があれば親・生成元と照合して残件を処理する。元利用者の `GET /api/v1/my/calendar?from=...&to=...` を元の期間で確認し、対象予定が返らないことも確認する。1か月の calendar 消失だけを全件掃除の証拠にしない。
6. チームを再び削除する必要があれば、保持されている当該チームの ADMIN/DEPUTY_ADMIN が `DELETE /api/v1/teams/{slug}` を実行する。SYSTEM_ADMIN 単独にはこの権限はない。対象管理者が利用できない場合、復元状態と掃除結果を運用記録に残し、既存の権限手順で担当を決める。権限付与や新しい削除経路を本手順から行わない。

## 途中失敗からの再開

途中で失敗したら、復元状態・成功済み ID・残件を記録して停止し、再取得した inventory と照合して未削除 ID から再開する。再試行時の予定404は、当該 ID が対象チームの論理削除済み行であることを読み取り確認し、対象違い・存在しない ID と区別する。認可403・サーバー障害を成功扱いにしない。掃除の0件確認が済むまでチームを再削除しない。

## 既設契約との対応

- 復元: F01.2 `02_api_design.md` の「論理削除復元」、`03_business_logic.md` の「論理削除復元フロー」。元公開範囲と `user_roles` の再利用は誤削除復旧の既設契約。
- 未削除の復元: 409（`TEAM_006`）という既存契約を維持する。F01.2 の API・業務仕様も現行ステータスに同期している。
- 予定削除: `ScheduleService.deleteSchedule` が既存認可・論理削除・activity発行を担い、`ScheduleEntity` の `deleted_at IS NULL` 制限が一覧と横断calendarから除外する。
- 本変更の検証には専用フィクスチャを使い、既存2026年9月の孤児や他戦役の資産は変更しない。実データの掃除は本手順による別の運用作業として扱う。
