package com.mannschaft.app.digest.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AI ダイジェスト非同期生成の起動リスナー（Issue #2990 L3 / ORDERING_ONLY 是正）。
 *
 * <p>{@code DigestGenerationService#generate} / {@code #regenerate} の業務トランザクション
 * （{@code timeline_digests} への GENERATING 行の INSERT・再生成時の DISCARDED 遷移）が
 * commit された<b>後</b>にのみ {@link DigestAsyncExecutor#generateAiDigestAsync} を起動する。</p>
 *
 * <h2>なぜ AFTER_COMMIT が要るのか（@Async だけでは足りない）</h2>
 * <p>{@code generateAiDigestAsync} は {@code @Async} なので業務TXには参加せず、通知の失敗で
 * ダイジェスト登録が巻き戻ることはない（＝台帳区分 {@code ORDERING_ONLY}）。しかし
 * {@code @Async} は「業務TXの commit を待つ」ことを何ら保証しない。非同期スレッドは
 * 呼び出し直後に走り出し、別コネクション・別TXで {@code digestRepository.findById} を行うため、
 * <b>業務TXが未 commit なら GENERATING 行が見えない</b>。その場合 executor は
 * {@code IllegalStateException} を catch して {@code markFailed} し、
 * <b>正常な生成要求に対して「ダイジェスト生成失敗」の通知を利用者へ送ってしまう</b>。
 * 本リスナーで commit 後に起動位置をずらすことで、この因果逆転を根本から断つ。</p>
 *
 * <h2>@Async を重ねない理由</h2>
 * <p>委譲先 {@link DigestAsyncExecutor#generateAiDigestAsync} 自体が {@code @Async} であり、
 * 本リスナーの処理は「引数を詰め替えて proxy を1回呼ぶ」だけである。ここで
 * {@code @Async("event-pool")} を重ねるとスレッドプールのホップが1段増えるだけで
 * 得るものが無いため、リスナーは同期のまま（AFTER_COMMIT コールバック上で即座に委譲）とする。
 * 委譲呼び出しは Spring AOP proxy を通るため {@code @Async} は有効である。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestAiGenerationDispatchListener {

    private final DigestAsyncExecutor digestAsyncExecutor;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "AIダイジェスト生成は利用者が明示的に要求した同期的な業務操作の後段であり、"
                    + "起動を止めると GENERATING のまま滞留してタイムアウト失敗になるため常時実行する")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDigestAiGenerationRequested(DigestAiGenerationRequestedEvent event) {
        try {
            digestAsyncExecutor.generateAiDigestAsync(
                    event.digestId(),
                    event.scopeType(),
                    event.scopeId(),
                    event.digestStyle(),
                    event.customPrompt(),
                    event.includeReactions(),
                    event.includePolls(),
                    event.includeDiffFromPrevious(),
                    event.language());
        } catch (Exception e) {
            // 起動自体に失敗した場合は握りつぶさず ERROR で観測可能にする。
            // ダイジェストは GENERATING のまま残り、generating_timeout_at 経過後に
            // 既存のタイムアウト回収経路が拾う（症状を隠さない＝根治原則）。
            log.error("AI ダイジェスト非同期生成の起動に失敗しました: digestId={}, scopeType={}, scopeId={}",
                    event.digestId(), event.scopeType(), event.scopeId(), e);
        }
    }
}
