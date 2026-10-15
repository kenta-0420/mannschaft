package com.mannschaft.app.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * スコープ（チーム・組織）配下のリソースに対する<b>存在秘匿つきの認可ゲート</b>（CMP-260923-0954）。
 *
 * <h2>なぜ必要か（存在オラクルの封鎖）</h2>
 * <p>ID で個別リソースを叩く API で、「他チームの利用者が実在 ID を叩いた応答」と「不在 ID を叩いた応答」が
 * 割れると（403 と 404、または 404 どうしでも {@code error.code} 違い）、応答の差だけで ID の実在が判る。
 * ID は連番で総当りが容易なため、これは 1 ビットの情報漏洩になる。本ゲートは拒否を次の 2 つに作り分ける。</p>
 * <ul>
 *   <li><b>越境</b>（当該スコープに所属していない）: 呼び出し側が渡す {@code notFoundCode} を投げる。
 *       これは<b>不在時に投げるコードそのもの</b>でなければならない（専用コードを新設すると、それ自体が
 *       「実在する」という答えになる）。</li>
 *   <li><b>同一スコープ内の権限不足</b>（所属はしているが許可条件を満たさない）: 403
 *       （既定 {@link CommonErrorCode#COMMON_002}）。所属者にはリソースの存在は既知なので隠す必要が無い。</li>
 * </ul>
 *
 * <h2>判定順（入れ替えてはならない）</h2>
 * <ol>
 *   <li><b>親リソースの生存確認は呼び出し側が本ゲートより先に行う。</b>不在・論理削除済みは
 *       {@code notFoundCode} で拒否する（CMP-260917-1136。SYSTEM_ADMIN の短絡より先に置かないと、
 *       SYSTEM_ADMIN だけが亡霊リソースを操作できてしまう）。親の不在と越境は同一応答になる。</li>
 *   <li>SYSTEM_ADMIN は通す。</li>
 *   <li>{@link AccessControlService#isAdminOrAbove}（user_roles と memberships の 2 系統を統合した有効ロール）。
 *       {@link AccessControlService#isMember} より<b>必ず先</b>に置く。memberships 行を持たず user_roles だけに
 *       ADMIN/DEPUTY_ADMIN を持つ利用者を、越境と誤判定して 404 にしないため。</li>
 *   <li>呼び出し側の許可条件（本人である、等）。</li>
 *   <li>許可されなかった場合のみ {@link AccessControlService#isMember} で越境か権限不足かを判定する
 *      （<b>拒否経路でのみ</b>クエリを追加し、許可経路のクエリ回数を増やさない）。</li>
 * </ol>
 *
 * <p>許可主体はエンドポイントごとに違う（C2: 判定順を全 EP に一律適用しない）。例えばメンバー操作
 * （{@link #requireMemberOrConceal}）では user_roles のみの ADMIN は<b>許可しない</b>が、所属者として 403 を返す。
 * 各呼び出し側は「EP 別許可主体表」に沿ってメソッドを選ぶこと。</p>
 *
 * <h2>適用しないもの</h2>
 * <p>{@code ?teamId=} で一覧を引く型は、非メンバーへの応答がチームの実在・可視性で割れない（常に同一の 403）
 * ため本ゲートの対象外（docs/security/01_authorization_baseline.md §3.3.1）。公開されているリソース
 * （PUBLIC のチーム・公開中の募集など）も 404 に倒さない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeConcealingAccessGate {

    private final AccessControlService accessControlService;

    /**
     * 管理操作の認可（SYSTEM_ADMIN、または当該スコープの ADMIN/DEPUTY_ADMIN）。
     *
     * @param userId       操作者
     * @param scopeId      リソース実体から解決したスコープ ID（パス・クエリの値を鵜呑みにしない）
     * @param scopeType    スコープ種別（{@code "TEAM"} 等）
     * @param notFoundCode 不在時に投げるコードと同一のコード
     * @throws BusinessException 越境なら {@code notFoundCode}、所属者の権限不足なら {@code COMMON_002}
     */
    public void requireAdminOrConceal(Long userId, Long scopeId, String scopeType, ErrorCode notFoundCode) {
        requireOrConceal(userId, scopeId, scopeType, () -> false, notFoundCode, CommonErrorCode.COMMON_002);
    }

    /**
     * 本人または管理者の認可（SYSTEM_ADMIN、リソースの所有者本人、当該スコープの ADMIN/DEPUTY_ADMIN）。
     *
     * <p>本人判定はメモリ上の比較でクエリを撃たないため、{@code isAdminOrAbove} より先に評価する
     * （本人経路の認可クエリ回数を是正前から増やさないため）。本人の現在の所属は問わない。</p>
     *
     * @param userId       操作者
     * @param scopeId      リソース実体から解決したスコープ ID
     * @param scopeType    スコープ種別
     * @param ownerId      リソースの所有者 ID（null なら本人経路は無い）
     * @param notFoundCode 不在時に投げるコードと同一のコード
     * @throws BusinessException 越境なら {@code notFoundCode}、所属者の権限不足なら {@code COMMON_002}
     */
    public void requireOwnerOrAdminOrConceal(Long userId, Long scopeId, String scopeType, Long ownerId,
                                             ErrorCode notFoundCode) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (ownerId != null && Objects.equals(ownerId, userId)) {
            return;
        }
        if (accessControlService.isAdminOrAbove(userId, scopeId, scopeType)) {
            return;
        }
        throw denial(userId, scopeId, scopeType, notFoundCode, CommonErrorCode.COMMON_002);
    }

    /**
     * メンバー操作の認可（SYSTEM_ADMIN、または当該スコープに在籍中のメンバー）。
     *
     * <p>在籍（memberships）を必須とする操作用。user_roles のみの ADMIN/DEPUTY_ADMIN は<b>許可しない</b>が、
     * 所属者として扱い 403 を返す（404 に化けさせない）。</p>
     *
     * @param userId           操作者
     * @param scopeId          リソース実体から解決したスコープ ID
     * @param scopeType        スコープ種別
     * @param notFoundCode     不在時に投げるコードと同一のコード
     * @param excludeSupporter true なら SUPPORTER を許可しない（403）
     * @throws BusinessException 越境なら {@code notFoundCode}、所属者の権限不足なら {@code COMMON_002}
     */
    public void requireMemberOrConceal(Long userId, Long scopeId, String scopeType, ErrorCode notFoundCode,
                                       boolean excludeSupporter) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (accessControlService.isMember(userId, scopeId, scopeType)) {
            if (excludeSupporter && accessControlService.isSupporter(userId, scopeId, scopeType)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
            return;
        }
        // 在籍していない: user_roles のみの管理者は所属者として 403、それ以外は越境として不在と同一応答。
        if (accessControlService.isAdminOrAbove(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        throw new BusinessException(notFoundCode);
    }

    /**
     * 汎用形。SYSTEM_ADMIN → ADMIN/DEPUTY_ADMIN → 呼び出し側の許可条件 の順に許可を判定し、
     * 許可されなければ越境（{@code notFoundCode}）か所属者の権限不足（{@code forbiddenCode}）かを作り分ける。
     *
     * @param userId        操作者
     * @param scopeId       リソース実体から解決したスコープ ID
     * @param scopeType     スコープ種別
     * @param permitted     管理者以外を許可する条件（管理者判定の後に評価される）
     * @param notFoundCode  不在時に投げるコードと同一のコード
     * @param forbiddenCode 所属者の権限不足に投げる 403 コード
     */
    public void requireOrConceal(Long userId, Long scopeId, String scopeType, BooleanSupplier permitted,
                                 ErrorCode notFoundCode, ErrorCode forbiddenCode) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (accessControlService.isAdminOrAbove(userId, scopeId, scopeType)) {
            return;
        }
        if (permitted.getAsBoolean()) {
            return;
        }
        throw denial(userId, scopeId, scopeType, notFoundCode, forbiddenCode);
    }

    private BusinessException denial(Long userId, Long scopeId, String scopeType,
                                     ErrorCode notFoundCode, ErrorCode forbiddenCode) {
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            // 越境（他スコープ／無所属）: 存在自体を隠す側。不在時と完全同一のコード。
            return new BusinessException(notFoundCode);
        }
        // 同一スコープ内の権限不足: 隠す必要が無い側。
        return new BusinessException(forbiddenCode);
    }
}
