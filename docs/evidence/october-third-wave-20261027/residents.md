# 第三陣 独立3住民探索

正式実機後、runId october-wave3-residents-20261027、source d3d6d05f942cf1fcb7a5a77352d1593092e1c30dで独立fresh BrowserContextの3住民が実UI探索した。sourceを読ませず、実UI login200、全browser/context CLOSED・driver exit0。殿は探索実施stageをpassedとした。個々の観測は全trusted:false/status:observed-unverifiedで、部分達成や未確認原因を保持する。

| 原persona / journey | 条件・目的 | 達成範囲 |
|---|---|---|
| W3-R1-DE / W3-R1-DE-old-org-cup-roster | ドイツ語運営者×素直、1280×720 | 旧org→新slugでquery/hash保持。deへ設定変更しCup名簿1名保存・UI/HTTP200で再読。PDF UI通信200だがPDF内部未検証。連絡欄400で部分達成 |
| W3-R2-MOBILE / W3-R2-OLD-TEAM-TO-CUP | スマホ片手操作×表示崩れ、360/390、ja | 旧team→組織→大会一覧→Cupへ自然到達。空number入力/common.loadで自身の登録状況確認はpartial |
| W3-R3-INTERRUPT / W3-R3-old-links-public-cup-interrupt | ADHD傾向×中断・放棄の非所属、1280×720、ja | login200後NET_NETWORK_CHANGED/白紙。再loginせず旧org/team公開情報へ復帰、別タブ中断後も基本情報へ戻る。公開Cupは見つからずpartial |

## 原観測と殿の裏取り

| 原観測 | 観測内容 | 裏取り・制約 |
|---|---|---|
| R1 O1/O2 | de組織基本情報/大会一覧・詳細の日本語ラベル残存 | 固有名/ユーザー名は除外。原因未確認 |
| R1 O3 | 保存buttonとfooter行の縦9px重なり候補 | 原観測保持。殿はp-dialog-content overflow:auto/client498/scroll562を確認し、通常scrollIntoView後に保存44px全体表示・footer重なりなし。意図した本文scrollで操作阻害不具合ではないと反証 |
| R1 O4 | 連絡欄categories/threads400、再試行400 | 殿UIでもCOMMON_001/400再現。contact-spaces200はTOURNAMENT/scopeId5・BULLETIN/CHAT refId17を返すが、UI queryはscope_typeありscope_id欠落。source DTO不整合をprivate contact-400-recon.mdへ記録。既存CMP重複なし、現時点未登録の別調査 |
| R2 R2-O1 | 空number入力とdisabled common.loadで登録確認が止まる | 任意IDは入力せず、仕様違反とは未断定 |
| R2 R2-O2 | 大会リンク高34px | 両幅でtap成功。操作不能とはしない |
| R2 R2-O3 | 360pxで部名省略/矢印、390pxで全文 | タブ内scrollとページ横パンを区別。全29snapでpage clientWidth=scrollWidth |
| R2 R2-O4 | dynamic module import pageerror2件、CSP report404 | 後続UI操作可能。原因未確認 |
| R3 W3-R3-01 | login200後NET_NETWORK_CHANGEDと白紙 | 精密発生時刻未記録。旧URLで復帰、原因未確認 |
| R3 W3-R3-02 | 旧org/teamから公開基本情報へquery/hash保持で復帰 | 観測を保持 |
| R3 W3-R3-03 | 非メンバー向けwidget非表示案内、公開Cup導線未発見 | 導線不存在・仕様違反とは断定しない |
| R3 W3-R3-04 | 同context別タブ中断後に基本情報復帰 | locator曖昧さや待機timeoutは製品欠陥へ算入しない |

O3反証はprivate root-repro/modal-geometry.json/03-modal-after-scroll.png、O4追加観測はcontact-contract-responses.json。原観測を修正せずoverlayの別review objectへ追記した。他観測は未検証状態を維持し、task-list/blockerへ自動昇格させない。

## 累積と後始末

canonical overlayはdocs/evidence/CMP-261015-1510/resident-overlay.json。最新main5ffの0649観測を含む12/12/19全objects、ID、run/source、既存metadataをdeep保持し、W3固有3/3/12だけ追加して15persona/15journey/31insight。ID重複0、既存validateRunOverlayで検証した。mainのrunId/environmentは維持し、W3 metadataはrunEnvironments[october-wave3-residents-20261027]に保存する。

R2/R3の新規業務データ0。R1は専用org19/team19/tournament5/division5/participant4の名簿1名と専用管理アカウント言語をUI変更。殿が探索fixture5kind全204→404と言語ja復元（UI更新200、html ja/combobox日本語）を確認した。回収の初回所有照合保留と自動承認却下は証拠補強後解消し、private履歴を保持。外部投稿/連絡送信なし。

private原本は.claude/runtime/october-third-wave-20261026/residents/R1〜R3のreport.md/summary.json/observations.json。住民PNGはprivate参照、正式実機の公開代表3PNGと区別。生応答body全量・PII・token・envは公開しない。
