# テスト資源cleanupの完了記録

## 専用テスト資源cleanup（先行完了記録）

ユーザーの「削除してよい」という明示承認を受け、専用合成schema `mobile_menu_20261016`・専用appuser・Redis DB13だけを削除した。削除helper完了に加え、別のread-only確認でschema不在・appuser不在・Redis DB13空を確認した。共有書込・outbox読取・Redis DB14への操作は行っていない。

以下は公開用 `owned-cleanup-final.sanitized.json` の原値。資格情報・私的設定を含まない。旧承認拒否・承認待ち・未削除の記録は当時の履歴として保持する。

```json
{
    "observedAt":  "2026-10-16T10:49:39.127489300Z",
    "apply":  true,
    "ownedPreflight":  true,
    "completed":  "redis13,schema,appUser",
    "sharedWrites":  false,
    "outboxRead":  false,
    "redis14Touched":  false,
    "userExplicitApproval":  true,
    "approvedRunRecordedAt":  "2026-10-16T19:50:04.4991382+09:00",
    "schemaAbsentVerified":  true,
    "appUserAbsentVerified":  true,
    "redis13EmptyVerified":  true,
    "separateReadOnlyVerification":  true,
    "oldBlockedHistoryPreserved":  true
}

```

## 初期共有テストfixtureのcleanup（完了）

ユーザーの具体的な「削除可」という明示承認を受け、今回の初期検証で作成したテストteam1件・organization1件・pending user2名だけを対象にした。team／organizationは通常DELETE APIが204を返し論理削除、pending user2名は既存`AuthCleanupBatchService`相当の限定トランザクションで論理削除した。物理削除や即時匿名化ではない。pending userはstatusと合成PIIを保持し、`deleted_at`・新UUIDv7・`updated_at`を更新した。

pending user処理ではverification token2件削除、user論理削除2件、`PENDING_USER_CLEANED_UP` audit2件を確認した。pending user限定SQLでは`batchOrEmailTriggered=false`を確認した。直接メール送信や全体batchは起動していないが、通常loginに伴う新規device検出等の副作用や配送有無は未検証であり、共有cleanup全体に「外部メールなし」とは断定しない。通常login／logoutに付随するauditと`last_login`更新はある。将来のreminder／purgeの通常フローは変更しておらず、将来配送の抑止も確認していない。

独立接続で4資源の論理削除を確認した。通常logout後のAPI helperが`completed=false`だった履歴は書き換えず、後続の固定したcleanup用refresh1行の通常失効相当トランザクションで補完した。失効1行・logout audit1件を適用し、他セッション失効数不変、全端末logout／user無効化／新規loginなしを確認した。失効後の別read-only最終確認を含め、共有4資源の処理とcleanup用セッションの後始末を確認済み。

前節の「共有書込・outbox読取・Redis DB14操作なし」と`sharedWrites:false`は、先行した専用schema／appuser／Redis13 cleanup時点の履歴であり、後続の明示承認済み共有fixture削除とは区別する。
以下は公開用 shared-cleanup-final.sanitized.json の原値。秘密・private設定・raw logは含まない。

```json
{
    "observedAt":  "2026-10-16T21:10:48.0502839+09:00",
    "userExplicitFixedFourCleanupApproval":  true,
    "allFourResourcesCleaned":  true,
    "team1247ApiDeleteStatus":  204,
    "organization618ApiDeleteStatus":  204,
    "pendingUserIds":  [
                           990560,
                           990561
                       ],
    "pendingLogicalDeletedCount":  2,
    "pendingVerificationTokensDeleted":  2,
    "pendingCleanupAuditCount":  2,
    "pendingStatusAndPiiPreserved":  true,
    "pendingUuidV7AndUtcUpdatedAtVerified":  true,
    "ownCleanupRefresh28780Revoked":  true,
    "ownLogoutSessionAuditCount":  1,
    "otherOwner23SessionRevocationsUnchanged":  true,
    "normalLoginLogoutAuditAndLastLoginSideEffects":  true,
    "originalApiLogout200PostconditionFailureHistoryPreserved":  true,
    "ownRefreshSupplementalCleanupReviewedAndApproved":  true,
    "separateReadOnlyFinalVerification":  true,
    "otherTeamOrganizationUserDeletes":  false,
    "allDevicesLogoutOrUserInvalidation":  false,
    "pendingFutureReminderAndPurgeUnmodified":  true,
    "futureDeliverySuppressionVerified":  false,
    "outboxRead":  false,
    "newLoginAfterLogoutFailure":  false,
    "rawSecretsEmitted":  false
}

```
