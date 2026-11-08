# CMP1320 main CI receipt

確認日時: 2026-10-25 15:36 UTC  
main SHA: `67ec2f72889612e916bda97b68f30db4ecb5f8a9`  
main merge: 2026-10-25 15:21:21Z (依頼元記録)  
実装 PR: [#4119](https://github.com/kenta-0420/mannschaft/pull/4119) — head `35801c64f0a2aff9123a4bdcbd8b931776fc3a37`

| Workflow run | Result / timing | Evidence |
|---|---|---|
| [Frontend — CI (PR Check), run 40206539292](https://github.com/kenta-0420/mannschaft/actions/runs/40206539292) | `success`; created 15:21:23Z, completed 15:35:45Z; exact main SHA | Install job `121208574220`: dependency install 15:21:45–15:21:54, audit gate regression tests success, npm audit success, OpenAPI type generation and drift check success, lint 15:21:58–15:23:15 success, type check 15:23:15–15:24:29 success, Vitest 15:24:29–15:35:41 success. Docker job `121208574353`: image build 15:21:48–15:34:22 success; `.output is self-contained` success; container HTTP response and Docker health check success 15:34:23–15:34:29; cleanup/log steps success. |
| [Frontend — Build & Deploy, run 40206539213](https://github.com/kenta-0420/mannschaft/actions/runs/40206539213) | `success`; created 15:21:23Z, completed 15:21:31Z; exact main SHA | Job `121208573978`: AWS credentials check success. Checkout, Node setup, dependency install, AWS configure, ECR login, image build/push, ECS task definition download/update, and ECS deploy steps all show `skipped` in the job's steps metadata. No production deployment or AWS resource change occurred in this run. |
| [秘密情報スキャン（gitleaks）, run 40206539110](https://github.com/kenta-0420/mannschaft/actions/runs/40206539110) | `success`; created 15:21:23Z, completed 15:21:57Z; exact main SHA | Job `121208573623`: Git regression test success; push diff range selection success; gitleaks diff scan success; report upload success. Weekly full-history scan job `121208574622` was skipped. |

結論: main の指定 SHA で必須の frontend checks、Docker の self-contained 検証と実 container HTTP/health 検証、秘密情報スキャンの回帰試験および差分 scan が成功。Build & Deploy workflow 自体は success だが、AWS credential check の後に cloud/ECR/ECS steps が skip されたため、本番変更は発生していない。

制限: GitHub Actions の通常ログ本文は調査していない（step/job metadata で結果を確認）。secret 値は取得していない。週次 full-history secret scan は今回未実行。PR #4119 の checks は依頼元記録で21 success/skip、ここでは重複取得していない。
