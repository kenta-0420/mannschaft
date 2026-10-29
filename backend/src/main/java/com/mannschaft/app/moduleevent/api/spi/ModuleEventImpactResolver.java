package com.mannschaft.app.moduleevent.api.spi;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;

import java.util.List;
import java.util.Optional;

/**
 * C / F 採用ロットが、確実配信イベントの業務上の影響と現在の通知対象を解決する SPI。
 *
 * <p>identify は初回登録時の scalar 抽出だけを行う。残りのメソッドは M の読取 TX を閉じ、
 * 呼び出し元 TX も停止した非 TX の段取りから呼ぶ。SPI の入口に TX を付けず、必要な読み取りは
 * 所有業務の読取 Bean へ委譲する。M の Repository、通知内部型、payload 再 deserialize に依存しない。</p>
 * <p>未接続・null・UNKNOWN を許可や復旧の根拠にしない。実装検体だけで将来の業務ロットを接続済みにしない。</p>
 */
public interface ModuleEventImpactResolver {

    /** 登録時に検査する resolver key の最大文字数。永続 key をクラス名としてロードしない。 */
    int MAX_RESOLVER_KEY_LENGTH = 100;

    /**
     * 永続化後も変えない安定識別子。形式は {@code [a-z][a-z0-9.-]{0,99}}。
     * 登録側は長さ・形式・Bean 間の一意性を検査し、不正・重複を起動失敗にする。
     */
    String resolverKey();

    /**
     * 発行時のイベントから ID と scope だけを取り出す。DB / HTTP 呼出しは禁止。
     * 自分のイベントでない、または安定 operation / episode ID が無ければ empty。
     * 乱数・時刻・payload hash で ID を補わず、binding の key は resolverKey と一致させる。
     * 既存 ReliableEvent 全体へ識別子を強制せず、接続する業務ロットだけが必要な scalar を持つ。
     */
    Optional<ModuleEventImpactBinding> identify(ReliableEvent event);

    /**
     * 業務の永続 workflow から close と実反映完了を確認する。M の listener 成功だけで判断しない。
     * CLOSED_COMPLETE は複数 event・複数発行 TX を含む対象が close され全反映済みの場合だけ。
     * 閉じた episode へ後発 event を再利用しない契約は、各採用ロットが検証する。
     * M 側も同 binding の未完了 publication が 0 であることを別途確認する。
     */
    ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding);

    /**
     * 現在許可された対象を userId 昇順で、afterUserId より大きいものから最大 limit 件返す。
     * 現 active / 所属 / role / scope / 実影響と phase の妥当性を再検証し、他 tenant を含めない。
     * 初回 cursor は 0。limit は呼出側で上限検証し、全件を List 化しない。
     * 正常な終端だけを空リストにし、接続不備・一時障害を正常な空ページに偽装しない。
     */
    List<ModuleEventNoticeRecipient> nextRecipients(
            ModuleEventNoticeDescriptor notice, long afterUserId, int limit);

    /**
     * 有界な候補 userId のうち現在も許可されたものだけを、重複なし・userId 昇順で返す。
     * 候補外の userId は返さない。sink 直前の再検証と配信直前 guard の singleton 判定で共有する。
     * 現 active / 所属 / role / scope / 実影響を確認し、不明・未接続を許可しない。
     */
    List<ModuleEventNoticeRecipient> eligibleRecipients(
            ModuleEventNoticeDescriptor notice, List<Long> candidateUserIds);
}
