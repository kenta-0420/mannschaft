# L1-B 実機確認（2026-10-27）

対象HEAD: a8b7d8b999973d2c138ffecce2fbe2233f6bb09a。実BE18082/FE18083、専用DB、通常UIログインとCookie、モック/route/認証ヘッダー注入なし。

real-ui.mjsはexit0、4case passed。純ADMINでチーム/組織管理コンソール表示、一般MEMBERのチーム管理拒否（scope topへ遷移・通知1回・管理API403）、ADMINの非所属組織管理拒否（scope topへ遷移・通知1回・管理API403）、未ログインでloginへ遷移を確認した。

rootはadmin-team-allowed/admin-allowed/member-denied/foreign-tenant-denied/anonymous-deniedの5PNGを画像として確認。foreign拒否は通知と遷移を確認した直後のloading画像で、移動先の安定表示を証明しない。探索で安定表示も確認する必要がある。

admin caseはconsole errorなし。member/foreignでは素早い画面遷移中にscope-tabs/dashboardのFailed to fetch記録があり、原因未確定。正常と断定せず探索で落ち着いた操作でも継続するか確認する。anonymousのme/teams401はログイン前の認可拒否と整合する。APIResponseContextで確認した管理API403はobservationsに記録（page response listとは別）。

証跡: /home/kenta/verification-evidence/CMP261027-l1b-runtime-final2/real/real-ui-results.json と同ディレクトリPNG。実機passedを探索passedの代わりにしない。

その後、runtime担当ターン終了後にrunnerがSIGHUPを受けFE停止。最初の3住民は接続不可で未実施停止（成功には含めない）。当時は同scratch/DBをroot管理で復旧待ちだったが、後段では復旧後に独立探索を完了し、最後に専用runtimeを撤去済み。元receiptのretainedは原形保持し、[再利用・最終撤去の抽出証拠](real/evidence.json)を別記する。

## 探索の統合確認

3住民が独立したBrowserContext/別アカウントで探索を完了した。実機4caseとは別に実施。

- 一般×素直（admin）: ダッシュボード→チーム管理→メンバー一覧→所属組織管理→dashboard帰還。21観測、console/requestfailed/HTTP400以上0。初期設定modalは後で決めるで閉じ、固定HEADのdismiss=visible false+sessionStorageのみ（PUT保存は別）をroot照合。
- ADHD傾向×中断復帰（member）: 予定画面→基本情報→戻る、別タブ→予定画面復帰、活動記録詳細→別タブ→復帰。200x183/204x2、console/pageerror/requestfailed0。表示月に予定項目がなく個別予定詳細の復帰は未検証。
- 一般×隙間狙い（third）: 非所属公開情報→team/org管理URL直打ち→公開scopeへ復帰、loading消失後に基本情報リンク操作成功。modulesの403とconsole403は拒否として記録。拒否toastは採取時点で未観測（実機では確認済み）。pageerrorなし。

rootはmemberの予定画面と活動記録復帰、outsiderの組織管理拒否後安定画面のPNGを画像として確認した。adminは画像を取得していないため視覚確認済みとはしない。全画面/全viewportの視覚監査や性能保証ではない。

adminの組織パネル切替前selector clickではpointer遮蔽があったが、選択パネルを通常UIで組織へ切替後は成功。固定HEAD DashboardScopeCarousel.vueは3panel常時mount/translateX配置であり、非選択DOMをselector指定した可能性と整合する。瞬間rect/画像はなく表示不具合とは確定しない。実機の速い遷移時Failed to fetchは、上記安定したadmin/member探索では継続再現しなかった。原因を断定せず原記録を保持する。

確定不具合0。製品/業務データ変更なし（UIログインなど通常auth動作のみ）。全住民browser終了後root stop-requestでサーバー停止、stillLive=[]を確認済み。探索原本はruntime-final2/exploratory下の各report/JSON/PNG。

## 完了文書への採用と対象範囲

[安全な実機・探索パッケージ](real/README.md)に原本hash索引と代表PNG3枚を同梱。member HTTP200は当時報告183件に対して保存原本再集計188件で、差5件の時刻/因果は未確定。204件数は両方2件。同一snapshotとして読み替えない。

本報告の実試験対象はa8b。統合HEAD f281ではupstream製品/画面変更もあり、同HEADでの再実施を主張しない。L1-B変更11fileのblob一致は[source bridge](build/integration-261028-source-bridge.json)、統合成立は[同HEAD CI](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860)で別々に評価する。f281統合CIは6fresh・失敗0で成功。後続のdocs-only追従HEAD e67のCI40662266445と実装mergeは待機中。
