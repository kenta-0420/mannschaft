# CMP-260902-0059 — 回帰

作成時刻: 2026-10-16T23:32:15.307004+00:00。実装head: `46d9ec59b1de525c8fb8f278694bb91507f9b907`、[実装PR #3918](https://github.com/kenta-0420/mannschaft/pull/3918)。

実装PR #3918は2026-10-16T23:49:18Zに通常squash merge済み（merge `66639eab7a2a2d9f903cec260ad658f8ddd07c84`）。最終headのCI成功とroot検分finding 0を確認し、27 ACを採用した。過去head/overlayの実行を最終headでの再実行とは扱わず、製品・試験入力の同一性根拠と最終CIへ接続する。

次の8回帰について、redとgreenを別入力として保全した。suiteの総件数がred/greenで増えた場合は、追加した境界caseの差を含む。setup失敗や試験selectorの補正を製品回帰redには数えない。

| 回帰 | red | 修正・green根拠 |
|---|---|---|
| 英語409が日本語 | deleted unit 16件/1failure。英語literalに日本語が返る | TEAM_006だけ登録済みi18n優先。`英語画面の409は英語で表示する`を含む17件green |
| 英語404が日本語 | deleted unit 17件/1failure | 固定メッセージのTEAM_001/TEAM_006をキー指定で翻訳優先。他errorCodeの具体的理由優先は維持。`英語画面の404は英語で表示する`を含む17件green |
| 新GETのAPI Gate宣言/本数台帳漏れ | ApiGateDeclarationGuardTest 7件/1failure、3688期待/3689実際 | 理由付きGATE_CONTROL_PLANEと台帳だけ同期。最新main追加も保持した281で7件green。parser/既存debt/否定対照は不変 |
| SYS復元後sidebar | BaseSidebar unit 19件/1failure。permissions200/modules403で表示不能 | 通常SYSで未使用modulesを取得しない。メンバーレンズ境界を追加した20件green。BE所属認可は不変更 |
| 初期直URLの拒否toast消失 | 実Toastのmount前→後回帰、計6件/1failure。空表示に英語拒否文言が無い | 初期だけNuxt準備後notify。計6件green＋実機005非SYS3件green |
| 復元後メニュー44px | 同じ実geometry caseで32pxを測定し>=44 assertion failure | menu/close/category/linkを最小44px。安定後・画面内の360/390実tapで1件green |
| ページ送り44px | 有効page1 40×40を測定し>=44 assertion failure | prev/current/next 44×44、current実tap/GET200。追加2caseのうち1件green |
| 予定詳細44px＋aria | 対照予定edit32×32を測定し>=44 assertion failure | edit/delete44×44と「編集」「削除」の意味名。対象予定実選択、編集/削除は未実行。追加2caseのうち1件green |

対応テスト: [deleted.spec.ts](https://github.com/kenta-0420/mannschaft/blob/46d9ec59b1de525c8fb8f278694bb91507f9b907/frontend/app/pages/system-admin/teams/deleted.spec.ts)、[BaseSidebar.spec.ts](https://github.com/kenta-0420/mannschaft/blob/46d9ec59b1de525c8fb8f278694bb91507f9b907/frontend/app/components/BaseSidebar.spec.ts)、[system-admin-toast.spec.ts](https://github.com/kenta-0420/mannschaft/blob/46d9ec59b1de525c8fb8f278694bb91507f9b907/frontend/tests/unit/middleware/system-admin-toast.spec.ts)、[team-restore-ui.spec.ts](https://github.com/kenta-0420/mannschaft/blob/46d9ec59b1de525c8fb8f278694bb91507f9b907/frontend/tests/e2e/real/team-restore-ui.spec.ts)。

| 実geometry red→green | red JUnit SHA256 | green JUnit SHA256 |
|---|---|---|
| menu | `51743b233e5300b423eb5eb1b10be6c5a5abdc1fabd39d902ffde8a443274be4` | `899093bd99d1ee7727922d5e0dcedff1a1ed25801cc72e8eef3c32af84af0bf8` |
| pager | `3510c84936f6a2c64e77f8fefd12295b9a6e68e842e07dc616c3323f9411a04d` | `659cbf205b8c187caef3dd077aaddee73d73a63ff6967ffb310b2480c054970a` |
| detail | `4af68e687d6ea1a122a13354f8b570e9e996e48a6922a7964fdbfcb39e5fab6b` | `659cbf205b8c187caef3dd077aaddee73d73a63ff6967ffb310b2480c054970a` |

別途、原初restoreのR1はbase `7e5f9b89989bbc93379b4fede66521d7cf1dfd5b` で実JWT/SecurityFilter/MySQLの1caseが204期待/404実際で失敗した（error/skip0、JUnit SHA `a24c1550444e266c189c70eac66e5f0411cc9f4e928d3822efabcd2255c2774b`）。この欠陥を保持し、[TeamRestoreSecurityFilterContractIT](https://github.com/kenta-0420/mannschaft/blob/46d9ec59b1de525c8fb8f278694bb91507f9b907/backend/src/test/java/com/mannschaft/app/team/TeamRestoreSecurityFilterContractIT.java)の復元/予定掃除ケースへ接続する。旧953時点11＋Committed34、追加一覧後422時点20＋Committed34＋guard12の66件greenは履歴根拠で、最終headの実行として申告しない。

enter中43px/安定後44px、hidden responsive DOMのfirst選択、装飾付きaccessible nameのexact不一致は試験補正として別記録。最終fixture前提のslug上限違反などsetup失敗も上記8回帰に含めない。
