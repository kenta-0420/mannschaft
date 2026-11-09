package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 臨時休業通知送信の冪等性の取りまとめ役（CMP-261104-0334）。
 *
 * <h2>トランザクション境界（意図的に {@code @Transactional} を付けない）</h2>
 * <p>本クラスは TX を持たず、送信本体 {@link EmergencyClosureService#sendClosure(Long, Long,
 * CreateEmergencyClosureRequest)}（別 Bean・{@code @Transactional}）へ、キーとハッシュを載せた
 * {@link IdempotentEmergencyClosureRequest} をプロキシ越しに渡す。
 * そのため送信本体の TX は本クラスへ戻る前に commit まで終わり、commit 時の例外も含めて
 * <b>rollback が完了した後に</b>ここで捕捉できる。本クラスが TX を持つと、同じキーの同時送信で
 * 後着側の UNIQUE 違反を捕捉しても、その TX は rollback-only のまま再読込も応答もできない。</p>
 *
 * <h2>流れ</h2>
 * <ol>
 *   <li>キー無し: 従来どおり毎回新規作成する（{@link Outcome#created()} = true → 201）。</li>
 *   <li>キー有り: 形式（UUID）を検証し小文字へ正規化、要求内容の SHA-256 を求める。</li>
 *   <li>既存行（チーム×送信者×キー）があれば、ハッシュ一致で既存結果を返し（新規作成なし → 200）、
 *       不一致なら {@link ReservationErrorCode#CLOSURE_IDEMPOTENCY_KEY_REUSED}（409）。いずれも副作用なし。</li>
 *   <li>無ければ送信本体を実行する。同じキーの同時送信で後着側は
 *       {@link EmergencyClosureEntity#IDEMPOTENCY_UNIQUE_CONSTRAINT} の違反で TX ごと巻き戻る
 *       （休業行・予約取消・枠減算・outbox・確認行は同一 TX、通知配送は AFTER_COMMIT のため発火しない）。
 *       <b>この制約名の違反だけ</b>を重複と判別し、新しい TX で既存行を読み直してハッシュを再照合する。
 *       他の整合性違反は重複扱いせずそのまま投げ直す。</li>
 * </ol>
 *
 * <p>認可は呼び出し元 Controller の {@code @PreAuthorize}（{@code isScopeAdmin}）が本クラスより前に行う。
 * 他テナントの ADMIN は既存行の照会に到達しないため、他者の送信結果は返らない。キーをチーム×送信者で
 * 区別するのは、同じチームの別の管理者・別チームの同じキーと衝突させないためである。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyClosureSendService {

    /** Java の {@code UUID.fromString} が許す短縮形を排除した、8-4-4-4-12 の16進表記。 */
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** MySQL の重複キーエラー（ER_DUP_ENTRY）。 */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final EmergencyClosureService emergencyClosureService;

    /**
     * 送信結果。{@code created} が true なら今回新規作成した（201）、false なら同じキーの既存結果を返した（200）。
     *
     * @param response 臨時休業レスポンス
     * @param created  今回新規作成したか
     */
    public record Outcome(EmergencyClosureResponse response, boolean created) {
    }

    /**
     * 臨時休業通知を冪等に送信する。
     *
     * @param teamId          チームID
     * @param operatorUserId  操作者ユーザーID
     * @param request         通知リクエスト
     * @param rawIdempotencyKey {@code Idempotency-Key} ヘッダの生値（無ければ null）
     * @return 送信結果
     */
    public Outcome send(Long teamId, Long operatorUserId, CreateEmergencyClosureRequest request,
                        String rawIdempotencyKey) {
        if (rawIdempotencyKey == null) {
            return new Outcome(emergencyClosureService.sendClosure(teamId, operatorUserId, request), true);
        }
        String key = normalizeKey(rawIdempotencyKey);
        String requestHash = EmergencyClosureRequestHasher.hash(request);

        Optional<EmergencyClosureResponse> sent = replay(teamId, operatorUserId, key, requestHash);
        if (sent.isPresent()) {
            log.info("臨時休業通知: 同じ冪等キーの再送のため既存結果を返す teamId={}, closureId={}",
                    teamId, sent.get().getId());
            return new Outcome(sent.get(), false);
        }
        try {
            return new Outcome(
                    emergencyClosureService.sendClosure(teamId, operatorUserId,
                            new IdempotentEmergencyClosureRequest(request, key, requestHash)), true);
        } catch (RuntimeException failure) {
            if (!isIdempotencyKeyCollision(failure)) {
                throw failure;
            }
            // 同じキーの同時送信で先着が commit 済み。こちらの TX は rollback 済みなので新しい TX で読み直す。
            EmergencyClosureResponse winner = replay(teamId, operatorUserId, key, requestHash)
                    .orElseThrow(() -> failure);
            log.info("臨時休業通知: 同じ冪等キーの同時送信を先着の結果に集約した teamId={}, closureId={}",
                    teamId, winner.getId());
            return new Outcome(winner, false);
        }
    }

    /**
     * 同じキーで送信済みなら、その結果を返す（内容が違えば 409）。未送信なら空。
     */
    private Optional<EmergencyClosureResponse> replay(Long teamId, Long operatorUserId, String key,
                                                      String requestHash) {
        return emergencyClosureService.findIdempotentClosure(teamId, operatorUserId, key)
                .map(sent -> {
                    if (!requestHash.equals(sent.requestHash())) {
                        throw new BusinessException(ReservationErrorCode.CLOSURE_IDEMPOTENCY_KEY_REUSED);
                    }
                    return sent.response();
                });
    }

    /** キーを UUID 形式に限定し小文字へ正規化する。生のヘッダ値は例外へ含めない。 */
    static String normalizeKey(String rawIdempotencyKey) {
        if (!UUID_PATTERN.matcher(rawIdempotencyKey).matches()) {
            throw new BusinessException(ReservationErrorCode.INVALID_CLOSURE_IDEMPOTENCY_KEY);
        }
        return rawIdempotencyKey.toLowerCase(Locale.ROOT);
    }

    /**
     * 例外が冪等キーの一意制約（{@link EmergencyClosureEntity#IDEMPOTENCY_UNIQUE_CONSTRAINT}）の違反かを判定する。
     *
     * <p>Hibernate が抽出した制約名で判別し、制約名が取れない場合だけ MySQL の重複キーエラー（1062）の
     * メッセージに制約名が含まれるかで判別する。他の制約・他の整合性違反は false（重複扱いしない）。</p>
     */
    static boolean isIdempotencyKeyCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint && constraint.getConstraintName() != null) {
                if (isTargetConstraint(constraint.getConstraintName())) {
                    return true;
                }
            }
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY
                    && sql.getMessage() != null
                    && sql.getMessage().contains(EmergencyClosureEntity.IDEMPOTENCY_UNIQUE_CONSTRAINT + "'")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static boolean isTargetConstraint(String constraintName) {
        String name = constraintName.replace("`", "").replace("'", "");
        int separator = name.lastIndexOf('.');
        return EmergencyClosureEntity.IDEMPOTENCY_UNIQUE_CONSTRAINT
                .equalsIgnoreCase(separator < 0 ? name : name.substring(separator + 1));
    }
}
