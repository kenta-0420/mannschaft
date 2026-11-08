# 住民C: MEMBER / ADHD傾向・中断復帰

- source: `frontend/test-results/alicization/october-first-wave-20261026/resident-member-interrupt/`
- source runId: `october-first-wave-20261026`; sourceHead: `03be10c6d87ceeec29d4b09f0bac4db0d391c514`; test account label: `MEMBER8`
- original journeyId: `member-shared-report-interrupt-resume`
- journey: 共有不具合報告の権限制限を確認し、中断後に通常利用へ復帰する。

360×800/390×844のmobile/touchを同一browser contextで探索。共有error report詳細は「アクセスできません / 管理者権限が必要です」の表示後dashboardへ移動し、詳細内容・操作は出なかった。別tab、履歴back/forward、更新後も認証を求められず通常利用へ戻れた。

Q&A上の一時DOM object-src違反では、`securitypolicyviolation`（object-src / blockedURI=data / enforce）、console遮断、ブラウザ自動POST `/api/v1/security/csp-reports` 204を観測。手動fetchは使わず、Q&A表示とメニュー操作が続いた。HTTPイベントの根拠は原本 `csp-object-observation.json`。参考画面: [Q&A継続状態](screenshots/member-16-csp-public-current.png)。この探索はCSP自動報告の使用感を観測したもので、DB保存は裏取りしていない。

制限: 試行中にlocator timeoutした操作や遷移/animation中の画像を成功証拠に含めない。長時間session期限切れ、offline復帰、書きかけフォームは対象外。CSPの異常報告未保存はこの住民観測の範囲外。
