# 診断の完全削除再試行

GDPR管理画面の診断再試行は、受付側TXで診断Repositoryを呼ばない。型付きのユーザーIDだけをイベントへ載せ、受付のコミット後に既存の `purge-pool` / `AFTER_COMMIT` 境界で診断自身の削除を実行する。診断の独立削除TXが正常に終了した後にだけ `markDomainSuccess(userId, "diagnosis")` を呼ぶ。失敗時はPENDINGを維持し、本文や例外メッセージをログへ複製しない。

受付応答は `succeeded=false, newStatus=PENDING, queued=true` と中立の完了待ちメッセージを返す。受付を削除成功として扱わない。既存の5引数DTO呼出は `queued=false` のまま、その他8同期ドメインの動作を維持する。管理画面はqueuedを失敗トーストと区別する。型生成は最終OpenAPI同期で統合する。

この修正の先行根拠は統合CIのD3T越境失敗。新しい受付・コミット・削除失敗・完了の実MySQL証明は後続検証が必要であり、DTOの純粋テストを非同期配送の証明にはしない。
