package com.mannschaft.app.digest.event;

/**
 * AI ダイジェストの非同期生成を要求するイベント（Issue #2990 L3 / ORDERING_ONLY 是正）。
 *
 * <p>{@code DigestGenerationService#generate} / {@code #regenerate} は業務トランザクションの内側で
 * 本イベントを publish するだけに留め、実際の {@code DigestAsyncExecutor#generateAiDigestAsync} 起動は
 * {@link DigestAiGenerationDispatchListener}（{@code AFTER_COMMIT}）が行う。</p>
 *
 * <h2>是正前の欠陥（因果順序の欠如）</h2>
 * <p>是正前は業務TXの内側から {@code @Async} な {@code generateAiDigestAsync} を直接呼んでいた。
 * {@code @Async} なので業務TXは巻き戻らない（＝ORDERING_ONLY）が、非同期スレッドは
 * <b>業務TXが commit する前に走り出す</b>。非同期側は別TX（別コネクション）で
 * {@code digestRepository.findById(digestId)} を行うため、READ COMMITTED では
 * <b>まだ commit されていない GENERATING 行が見えず</b>
 * {@code IllegalStateException("Digest not found")} で失敗しうる。
 * その結果、実際には正常に受理された生成要求に対して
 * <b>「ダイジェスト生成失敗」という嘘の通知が利用者へ届く</b>。
 * 逆に業務TXが後からロールバックした場合は、存在しないダイジェストの完了通知だけが残る。</p>
 *
 * <p>本イベントは描画済み文字列や {@code LocalDateTime} を載せない。ID と種別（enum 名）、
 * および生成パラメータ（config 由来の真偽値・言語）のみを運ぶ。</p>
 *
 * @param digestId                 生成対象のダイジェストID
 * @param scopeType                スコープ種別（{@code DigestScopeType} の enum 名）
 * @param scopeId                  スコープID
 * @param digestStyle              ダイジェストスタイル（{@code DigestStyle} の enum 名）
 * @param customPrompt             追加プロンプト（NULL 可。リクエスト由来で永続化されないため event で運ぶ）
 * @param includeReactions         リアクションを含めるか
 * @param includePolls             投票を含めるか
 * @param includeDiffFromPrevious  前回ダイジェストとの差分を含めるか
 * @param language                 生成言語
 */
public record DigestAiGenerationRequestedEvent(
        Long digestId,
        String scopeType,
        Long scopeId,
        String digestStyle,
        String customPrompt,
        Boolean includeReactions,
        Boolean includePolls,
        Boolean includeDiffFromPrevious,
        String language) {
}
