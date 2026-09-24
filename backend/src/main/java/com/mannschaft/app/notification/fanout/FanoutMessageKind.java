package com.mannschaft.app.notification.fanout;

/**
 * fan-out 通知の文面テンプレート種別（Issue #2871）。
 *
 * <p>enqueue は「描画済みの文字列」ではなく<b>本 enum ＋ 型付き引数</b>を受け取り、
 * その場で 6 配信ロケールぶんの文面を描画して子表 {@code notification_fanout_job_messages} に保存する。
 * ジョブ表に {@code title_key} / {@code body_key} という<b>キー文字列の列を持たせない</b>のは、
 * 「存在しないキーが列に入りうる」状態を作らないためである（enum なら参照時点でコンパイル時に閉じる）。</p>
 *
 * <h2>村の 4 分岐をキーで表す理由</h2>
 * <p>行事追加 / 明日開催 / 寄合日程確定 / 祭り開始は、語順・文意・助詞まで異なる別テンプレートであり、
 * 「種別」を引数として properties に渡すと properties 側に分岐ロジックを持ち込むことになる
 * （{@code choice} 形式の温床）。そこで 4 種を別の {@link FanoutMessageKind} として持ち、
 * <b>引数は行事名ただ 1 つ</b>に保つ。</p>
 *
 * <h2>引数は「利用者が書いた中身」だけ</h2>
 * <p>{@code arg0} / {@code arg1} に載せるのはアンケート名・行事名・予定名といった
 * <b>利用者が書いた文字列</b>のみであり、これらは翻訳せずそのまま配る。翻訳するのは
 * properties 側の「枠」だけである。引数はすべて {@code String}（数値・日時は 1 つも無い）ため、
 * {@code MessageFormat} の数値既定書式（3 桁区切り）の影響を受けない。</p>
 */
public enum FanoutMessageKind {

    /** アンケート公開（組織スコープ×ALL）。{@code arg0} = アンケート名。 */
    SURVEY_PUBLISHED("notification.fanout.survey.published.title",
            "notification.fanout.survey.published.body", 1),

    /** キープ→予定 変換（TEAM スコープ）。{@code arg0} = キープ（予定）のタイトル。 */
    SCHEDULE_KEEP_CONVERTED("notification.fanout.scheduleKeep.converted.title",
            "notification.fanout.scheduleKeep.converted.body", 1),

    /** シフト公開。可変部分なし＝全文がアプリの文言。 */
    SHIFT_PUBLISHED("notification.fanout.shift.published.title",
            "notification.fanout.shift.published.body", 0),

    /** 村: 新しい行事が追加された。{@code arg0} = 行事名。 */
    VILLAGE_EVENT_ADDED("notification.fanout.village.eventAdded.title",
            "notification.fanout.village.eventAdded.body", 1),

    /** 村: 明日その行事が開催される。{@code arg0} = 行事名。 */
    VILLAGE_EVENT_TOMORROW("notification.fanout.village.eventTomorrow.title",
            "notification.fanout.village.eventTomorrow.body", 1),

    /** 村: 寄合の日程が決まった。{@code arg0} = 寄合名。 */
    VILLAGE_MEETING_CONFIRMED("notification.fanout.village.meetingConfirmed.title",
            "notification.fanout.village.meetingConfirmed.body", 1),

    /** 村: 祭りが始まった。{@code arg0} = 祭り名。 */
    VILLAGE_FESTIVAL_STARTED("notification.fanout.village.festivalStarted.title",
            "notification.fanout.village.festivalStarted.body", 1);

    private final String titleKey;
    private final String bodyKey;
    private final int argCount;

    FanoutMessageKind(String titleKey, String bodyKey, int argCount) {
        this.titleKey = titleKey;
        this.bodyKey = bodyKey;
        this.argCount = argCount;
    }

    public String titleKey() {
        return titleKey;
    }

    public String bodyKey() {
        return bodyKey;
    }

    /** 本種別が使う引数の本数（0〜2）。可観測性・テストの照合軸。 */
    public int argCount() {
        return argCount;
    }
}
