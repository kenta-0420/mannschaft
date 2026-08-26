package com.mannschaft.app.village.service;

import com.mannschaft.app.village.dto.VillageInvitationAcceptResponse;
import com.mannschaft.app.village.dto.VillageInvitationCreateRequest;
import com.mannschaft.app.village.dto.VillageInvitationIssueResponse;
import com.mannschaft.app.village.dto.VillageInvitationSummary;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 村招待サービス（<b>骨格スタブ</b>。試練（テスト先行）が参照する型を用意するためだけのもの）。
 *
 * <p>本クラスは中身を持たない。すべてのメソッドは {@link UnsupportedOperationException} を投げる。
 * 例外を握りつぶさず正直に落とすことで、対応する試練が「実装が無いから red」であることを
 * 明示する。出陣（実装）でここを実体に差し替えること。</p>
 *
 * <p>非公開(UNLISTED)村の存在秘匿を破らないことが本機能の最重要契約である。
 * 詳細な受け入れ条件は {@code VillageInvitationServiceTest} の Javadoc を参照。</p>
 */
@Service
@RequiredArgsConstructor
public class VillageInvitationService {

    private final VillageInvitationRepository invitationRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageAccessGate villageAccessGate;

    /** 招待を発行する（村長・長老のみ）。平文トークンはこの戻り値でのみ返す。 */
    @Transactional
    public VillageInvitationIssueResponse issue(
            UUID villageId, Long actorUserId, VillageInvitationCreateRequest request) {
        throw new UnsupportedOperationException("未実装: 出陣で実装すること（試練が red であるべき箇所）");
    }

    /** 自村の招待一覧を返す（村長・長老のみ）。平文トークンは含めない。 */
    @Transactional(readOnly = true)
    public List<VillageInvitationSummary> list(UUID villageId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: 出陣で実装すること（試練が red であるべき箇所）");
    }

    /** 招待を失効させる（村長・長老のみ／冪等）。 */
    @Transactional
    public void revoke(UUID villageId, UUID invitationId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: 出陣で実装すること（試練が red であるべき箇所）");
    }

    /**
     * 招待を受諾して村人になる。
     *
     * <p>村IDを引数に取らない。トークンだけで村を解決することで、
     * 「その村が実在するか」を呼び出し側に一切明かさない。</p>
     */
    @Transactional
    public VillageInvitationAcceptResponse accept(String token, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: 出陣で実装すること（試練が red であるべき箇所）");
    }
}
