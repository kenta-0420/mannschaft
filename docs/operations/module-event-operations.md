# モジュールイベントの隔離・再開運用（L1-M）

本書は、確実配信イベントの未完了監視と、SYSTEM_ADMIN が隔離した配信を1件ずつ再開する手順を示す。L1-M は実装・受け入れ検証中であり、ここに記す機能案内は全体合格や本番稼働済みの宣言ではない。完了状態は[戦役台帳](../task-list.md)を正本とする。

## 対応前に確認すること

未完了は業務間の反映がまだ完了していない状態、隔離（`QUARANTINED`）は自動再投入を停止した状態を表す。隔離の原因を修正し、対象業務の再実行の安全性を確認してから再開する。単に警報を消す目的で再開しない。

管理操作は SYSTEM_ADMIN 限定である。チーム・組織の ADMIN / DEPUTY_ADMIN だけでは利用できない。通常の[ログイン仕様](../features/F01.1_auth.md)と[認証Cookie・セッション・CSRF方針](../security/02_cookie_and_session.md)に従って認証する。Cookie や認証情報をコマンド引数、理由、調査記録に転記しない。本機能専用のトークン発行や保存手順は追加していない。

初回の個別再開UIは追加していない。[既存バッチ管理](../features/F10.X_batch_catalog.md)の実行操作はバッチ単位であり、ここで示す publication ID 指定の再開とは異なる。隔離の解除には次の実APIを使用する。

## SYSTEM_ADMIN 向けAPI

追加したHTTP入口は以下の2本だけである。パスの `publicationId` は一覧に返る UUID を使う。

| 操作 | リクエスト | 正常応答 |
|---|---|---|
| 隔離一覧 | `GET /api/v1/system-admin/module-events` | 200、`data` とページ情報 `meta` |
| 1件再開 | `POST /api/v1/system-admin/module-events/{publicationId}/resume` | 204、応答本文なし |

一覧の `status` は省略または厳密に `QUARANTINED` のみを受け付ける。省略しても隔離一覧となる。`page` は0以上（既定0）、`size` は1〜100（既定20）。隔離時刻の降順、同時刻では publication ID の昇順で返す。0件は正常な空の `data` であり、取得失敗と区別する。

各行には `publicationId`、`eventType`、`listenerId`、`publicationDate`、`lastAttemptAt`、`quarantinedAt`、`retryCount`、`failureCode` が含まれる。イベントpayloadと例外本文は含めない。時刻は既存アプリの日時形式で返す。

再開のJSON本文は `reason` だけを指定する。前後空白を `trim` した後の理由が1〜500文字であることが必要で、null・空・空白だけは拒否する。理由には原因と修正確認の要点を記し、秘密情報や個人情報、payload、例外全文を貼らない。操作者は認証情報から取得するため、リクエストで指定しない。

| HTTP状態 | 意味・対応 |
|---|---|
| 400 | 一覧条件、UUID、JSON、再開理由などが不正。入力を確認する |
| 401 | 未認証または認証が無効。既存のログイン経路で認証を確認する |
| 403 | SYSTEM_ADMIN 権限がない。権限を迂回しない |
| 404 | 指定した publication が存在しない。一覧から対象を再確認する |
| 409 | 既に再開済み、隔離状態でない、完了済み、または同時操作との競合。一覧と業務状態を再確認する |
| 5xx | サーバー側の処理失敗。成功と扱わず原因を調べる |

## 原因修正後に1件だけ再開する

1. 監視と隔離一覧で事象を確認する。例えば `GET /api/v1/system-admin/module-events?status=QUARANTINED&page=0&size=20` で、対象の event type、listener、時刻、失敗コードを照合し、再開する `publicationId` を1件選ぶ。
2. 対象業務の担当と根本原因、修正反映、同じ処理を再実行しても整合性を保てることを確認する。業務ごとの完了条件・実影響・現在の権限を確認できない場合は再開判断を保留する。
3. 同じ認証済み経路で、そのIDの `POST .../{publicationId}/resume` に修正確認を含む理由を送る。全件再開や手動SQLによる状態更新は行わない。
4. 204を受けたら再開受付として記録する。再開は隔離から `PENDING` へ戻して再試行回数を0にする操作であり、その場で業務処理の成功を保証しない。409や通信途絶時は同じ操作を連打せず、まず一覧と業務状態を再確認する。
5. 通常の再投入を待ち、隔離一覧、未完了指標、既存の通知ベル／通知一覧、対象業務の実際の反映を確認する。隔離一覧から消えたこと、受付、enqueue、listener単体成功、部分完了だけでは復旧と判定しない。再隔離や遅延継続なら原因調査へ戻る。

再開の状態変更と専用監査の追記は同じ moduleevent トランザクションで確定する。同時操作は世代を比較して1件だけ更新し、勝者だけが監査を残す。監査保存・flushに失敗した場合も状態変更と一緒に rollback する。監査は `publicationId`、認証した `actorUserId`、正規化した `reason`、作成時刻を保持する。

## 監視・再投入の読み方

| 固定メトリクス名 | 意味 |
|---|---|
| `module_event.incomplete.count` | 隔離を含む全未完了件数 |
| `module_event.incomplete.oldest.age.seconds` | 最古の未完了の経過秒数。未完了なしは0 |
| `module_event.replay.accepted` | 再投入の受理commit後に増えるカウンター。物理キューへの仮受付は数えない |
| `module_event.quarantined.count` | 隔離件数 |
| `module_event.pattern_f.completion.failure` | Pattern F の業務側から明示報告された完了失敗の件数。一般listener失敗から推定しない |

指標名は固定で、IDやpayloadをラベルへ追加しない。監視処理は最古の未完了が厳密に30分を超えた場合に `OLDEST_PENDING_30_MIN`、隔離遷移のcommit後に `QUARANTINED` の運用signalを発行する。既存 errorreport 経路へ HIGH として接続し、固定メッセージ `MODULE_EVENT_OLDEST_PENDING_30_MIN` / `MODULE_EVENT_QUARANTINED` を使用する。業務payloadや元の例外本文をこのsignalへ載せない。

通常再投入は `module-event-replay` が60秒周期で起動し、発行から5分以上経過した未完了を1回最大100件扱う。実行leaseは5分で、未期限の処理を重ねて実行せず、期限切れは再取得の対象となる。再試行受理回数が10回に達した未完了は隔離し、11回目の業務実行へ進めない。

**これらの周期・cutoff・件数・lease・実行容量はM18の性能実測で未確定の暫定値である。** M18は検体不備の修正・検証中で、成立した負荷測定や性能判定はまだない。処理時間や運用上の復旧所要時間を保証する値として扱わず、本書から新しい運用パラメータは追加しない。

## 影響を受けた利用者への通知と復旧条件

接続済みの業務では、継続遅延（`DELAYED`）、隔離（`QUARANTINED`）、実復旧（`RECOVERED`）を既存の通知ベル・通知一覧へ配送する。遅延は発行から厳密に30分を超えた未完了が対象となる。通知先は当該業務の現在の管理者と実際に影響を受ける利用者で、過去の所属・権限を固定した宛先ではない。既存の[通知インボックス仕様](../features/F04.11_notification_inbox/README.md)も参照する。

各C / F採用ロットは `ModuleEventImpactResolver` を実業務へ接続し、次を担う必要がある。基盤や検証用resolverの存在だけで、全業務を接続済みとは扱わない。

- 安定した operation / episode とscopeを識別し、対象業務の永続workflowがcloseされ、全反映が完了した場合だけ `CLOSED_COMPLETE` を返す。閉じたepisodeへ後発イベントを混ぜない。
- 現在のactive状態、所属、管理者ロール、scope、実影響を評価し、現在のテナント外の利用者を含めない。有界ページで対象を返し、sink直前と配信直前にも現在の許可を再確認する。
- 未接続・不明・null・一時障害を、復旧や許可、正常な空ページへ置き換えない。

M側も同じbindingに属する未完了publicationが0件であることを確認する。listener成功で残るのは復旧判定を待つ予約であり、業務の `CLOSED_COMPLETE` とM側の未完了0件を満たすまで実復旧として配送しない。現在の権限や実影響が確認できない場合も配送を許可しない。

## 保存と未完了の境界

通知予約とdescriptorは自動物理削除しない。`ModuleEventNoticeOutboxSource.sweep` が正常に0件を返すのはこの実装上の方針であり、削除処理が完了したことを意味しない。

再開監査の actor / reason の保持期間、退会時匿名化、export、DBでの2年保持とarchiveの具体的な適用は、ユーザー判断および他領地の AUTH / common-core、PRIVACY / C との調整が未回答・未実装である。一般の[監査ログ仕様](../features/F10.3_audit_logs.md)だけで、この専用監査への中央監査一覧接続や保存・削除実装が完了したとは扱わない。無期限保存の承認を意味しない。

局所の実DB・通知・復旧・運用alertの検証は進んでいるが、全CI、OpenAPI生成前後の照合、M18性能実測、M21実機と探索、最新mainへの着地の全体完了は未証明である。L1-Mや親戦役の終了、全C / F業務の接続完了として読まない。

## 実装の参照先

- [管理HTTP入口](../../backend/src/main/java/com/mannschaft/app/moduleevent/controller/SystemAdminModuleEventController.java)、[一覧・再開と監査TX](../../backend/src/main/java/com/mannschaft/app/moduleevent/admin/ModuleEventAdminService.java)
- [固定指標・運用signal](../../backend/src/main/java/com/mannschaft/app/moduleevent/ModuleEventOperations.java)、[再投入バッチ](../../backend/src/main/java/com/mannschaft/app/moduleevent/ModuleEventReplayBatch.java)、[再投入と隔離](../../backend/src/main/java/com/mannschaft/app/moduleevent/ModuleEventPublicationStore.java)
- [採用業務の責務](../../backend/src/main/java/com/mannschaft/app/moduleevent/api/spi/ModuleEventImpactResolver.java)、[通知予約](../../backend/src/main/java/com/mannschaft/app/moduleevent/notice/ModuleEventNoticeLifecycleService.java)、[自動削除をしない通知source](../../backend/src/main/java/com/mannschaft/app/notification/moduleevent/ModuleEventNoticeOutboxSource.java)
