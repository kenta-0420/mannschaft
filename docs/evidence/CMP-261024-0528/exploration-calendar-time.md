⚔️足軽2（住民・一般×時間破壊）: 月跨ぎ登録と編集日時の保持までは達成したが、翌月カレンダーからの発見は果たせませぬ。

sourceHead: 73d9bd8e2c / runId: CMP-261024-0528 / journeyId: dashboard-day-calendar-month-boundary
環境: FE3001・BE8081、1440x900/ja、ブラウザAmerica/Los_Angeles、利用者Asia/Tokyo。単一context・ログイン1回。
目的: 月末から翌月へまたがる自分の予定を登録し、両日で見つけ、編集日時を確かめる。

31日セルから追加フォームへ到達。開始23:30選択で終了が翌日00:30へ調整された。POST /api/v1/me/schedules は201、タイトル「時間境界-足軽2-261024」、id23。レスポンスtimeは startAt=2026-10-31T23:30:00+09:00、endAt=2026-11-01T00:30:00+09:00。10月31日の一覧、詳細、編集フォームは意図した日時を保持（step-8/10/11/12、edit-aria.txt、edit-inputs.json）。

翌月へ進めると11月カレンダーに予定が見えず、11月1日セルは予定追加フォームを開いた（step-14/16、november-aria.txt、november-first-aria.txt）。UIのGET /api/v1/me/schedules と /api/v1/my/calendar は、from=2026-11-01T00%3A00%3A00%2B09%3A00&to=2026-12-12T23%3A59%3A59%2B09%3A00 に対し双方200 data:[]。10月範囲from=2026-09-27T00:00:00+09:00,to=2026-11-07T23:59:59+09:00ではid23が返った。原因断定はせず殿へ共有済み。

実画像step-8/12/14/16をview_imageで確認。http.json、date-http-evidence.json、console.jsonを保全。consoleのCSP eval拒否とPermissions-Policy warningは別途ログに残るが、目的に沿う予定操作APIのエラー応答はなかった。

後始末: 自作id23のみ実UIから削除、DELETE /api/v1/me/schedules/23=204、10月UIと取得一覧からタイトル消失を確認。DB操作なし。他者データ変更なし。探索scriptは資格情報を環境変数参照へ置換して証跡へ保全し、自作spec/configのみ除去。製品コード変更・commitなし。
