⚔️足軽3（住民・ADHD傾向×中断放棄）: 目的を達しました。未実証の気づきは殿の裏取りを願います。

登録元: アリシゼーションテスト / personaId: interruption-approved
runId: CMP-261021-1347 / journeyId: alice-interruption-approved-20261022
environmentRunId: clipv3-1792574010514-3a8a8367
実施: 2026-10-22 08:23:47～08:28:42 JST
専用FE localhost:51359 / BE localhost:18724、air-adultのみ、通常UIログイン1回、BrowserContext1個。

1. 新しい明示承認の後、identity guardとpublicLoginEnvironmentPreflightを通過。通常ログインでdashboardへ到着。前回BLOCKED記録は別フォルダーに保存したままでござる。
2. 恐竜の入口が初期viewportになく、マイページへ行ったが見つからず。設定へ行くと「恐竜の部屋の設定」が見つかり、クリックして /my/ranch へ到達。
3. 青い飛行恐竜「試験air-a · 成体」と140 XPを確認。「遊び方」モーダルで「離れても大丈夫」を読み、Escapeで閉じることができた。
4. 「ごはん」を1回クリック。「ごはんをあげました。成長 20 XP。」と160 XP、無料ケア残枠40 / 100 XPを確認。
5. 画面を下へスクロールし、動きの選択欄から「動きを止める」を選択。別タブabout:blankを開いて前面化し、元へ戻る。選択は保持されていた。ただし元document.hiddenはfalseであり、実非表示化の検証とは扱わぬ。
6. 「保存する」へフォーカスしてEnter。設定画面へ離れ、ブラウザ戻るで帰還。自分の名前・160 XP・「動きを止める」が保持され、同じ恐竜の静止画を確認できた。動き欄からTabで「音を使う」のcheckboxへ移ることも確認。
7. 再読込後にも同じ名前・160 XP・停止設定を確認。エラー画面やログアウトは発生せず、部屋到達後に採取したpageerrorとHTTP>=400は0件。
8. finallyで自分のcontext/browserを終了。closed.jsonでisConnected=false、contexts=0、HTTP listener不使用を確認。driver session 56950はexit code 0。

視覚: 1280×720、1440×900、1920×1080の100%と、1024×576／1152×720の125% viewport近似を撮影・実画像確認。実ブラウザzoom確認とは区別。21枚すべて直接確認した。各状態scrollWidth=clientWidthで横はみ出しなし。部屋上部の追加4幅と最終設定欄でoverflow hidden/clipの文字切れ候補なし。sticky header/navと本文が操作を妨げる重なりは画像上で観測せず。小さい高さでは下のケアや設定へ縦スクロールが必要で、到達できた。

未実証の気づき INT-VIS-01: 1280×720 /my/ranch 設定欄の「描画スタイル」「動き」外枠は各467×42px、combobox本体425×40px。音量inputは241×42px。44px基準未満。保存・非表示・休止ボタンは44px高。checkbox自体は20×20pxで「音を使う」labelが別にあり、label込みの実効領域は別途裏取りが必要。証拠17-final-preferences.png、preference-geometry.json、control-targets.json。原因は断定しない。

文字の一時観測 INT-TEXT-01: 保存後のbodyテキストで一度「この姿の素材は準備中です。文字と操作で引き続き利用できます。」を観測した。戻る後と再読込後は「同じ個体の静止画で表示しています。」と青い恐竜を確認。保存直後の上部viewport画像は未撮影であり、画像欠落の不備とは断定しない。runtime-log.jsonのfinalTextとreport.jsonに観測の限界を保持。

限界: document.hidden=true、logout後復帰、ブラウザ全終了から再ログイン、未保存のroute離脱は未実施。バリデーションエラーは自然に遭遇せず、目的外の状態を強制作成していない。専用アカウントのごはん+20XPと動き停止設定はUI上で保存したまま。

ハーネス: 初回Windows importのC: ESM URLエラーはbrowser起動前。file URL表記へ修正し同じ起動を実施。不可視desktopメニューボタンのtimeout、重複heading/閉じるlocatorのstrict errorは選択ミスであり製品findingではない。
