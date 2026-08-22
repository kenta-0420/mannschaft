package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 村サービスの単体試練で {@link VillageAccessGate} を扱うための共通ヘルパ。
 *
 * <h2>なぜ「モックに実物を委譲させる」のか</h2>
 * <p>15 の村サービスは村の存在確認を各自の {@code loadActiveVillage} から
 * {@link VillageAccessGate} へ移した。素直に {@code @Mock VillageAccessGate} を足すだけだと、
 * 既存の単体試練が積み上げてきた {@code given(villageRepository.findById(...))} の stub は
 * <b>誰にも読まれなくなり</b>、ゲートのモックが黙って {@code null} を返すため
 * 全テストが NPE で一斉に落ちる。stub をテストごとに書き換えて回るのは
 * 「テストを実装に合わせて緩める」方向であり、番人を弱らせる。</p>
 *
 * <p>そこで本ヘルパは、モックのゲートに<b>実物の {@link VillageAccessGate}</b>（テストが
 * stub しているのと同じモックのリポジトリを注入したもの）へ委譲させる。これにより</p>
 * <ul>
 *   <li>既存試練の {@code villageRepository.findById} stub はそのまま生き続ける</li>
 *   <li>可視性ゲートの判定は<b>実物のロジックが実行される</b>（モックで素通りしない）ので、
 *       非公開村の秘匿が単体試練からも検証できる</li>
 * </ul>
 *
 * <p>公開(PUBLIC)村では実物ゲートは追加クエリを一切撃たないため、
 * PUBLIC 村を使う既存試練はメンバーシップの stub を足さずに従来どおり通る。</p>
 */
public final class VillageAccessGateTestSupport {

    private VillageAccessGateTestSupport() {
    }

    /**
     * SYSTEM_ADMIN 判定を常に false とする（＝一般ユーザー前提の）委譲を仕込む。
     *
     * @param gateMock             {@code @Mock VillageAccessGate} で宣言したモック
     * @param villageRepository    テストが stub している村リポジトリのモック
     * @param membershipRepository テストが stub している村メンバーシップリポジトリのモック
     * @return 委譲先として生成した実物ゲート
     */
    public static VillageAccessGate delegateToRealGate(VillageAccessGate gateMock,
                                                       VillageRepository villageRepository,
                                                       VillageMembershipRepository membershipRepository) {
        return delegateToRealGate(gateMock, villageRepository, membershipRepository,
                Mockito.mock(AccessControlService.class));
    }

    /**
     * SYSTEM_ADMIN 判定まで制御したい場合の委譲を仕込む。
     *
     * @param accessControlService SYSTEM_ADMIN 判定に使うモック（{@code isSystemAdmin} を stub できる）
     */
    public static VillageAccessGate delegateToRealGate(VillageAccessGate gateMock,
                                                       VillageRepository villageRepository,
                                                       VillageMembershipRepository membershipRepository,
                                                       AccessControlService accessControlService) {
        VillageAccessGate real =
                new VillageAccessGate(villageRepository, membershipRepository, accessControlService);

        // lenient: ゲートを一度も呼ばないテストでも UnnecessaryStubbing で落とさないため。
        lenient().doAnswer(inv -> real.loadActiveVillage(inv.getArgument(0), inv.getArgument(1)))
                .when(gateMock).loadActiveVillage(any(), any());
        lenient().doAnswer(inv -> real.loadReadableVillage(inv.getArgument(0), inv.getArgument(1)))
                .when(gateMock).loadReadableVillage(any(), any());
        lenient().doAnswer(inv -> real.isVisibleTo(inv.getArgument(0), inv.getArgument(1)))
                .when(gateMock).isVisibleTo(any(), any());
        lenient().doAnswer(inv -> real.loadVillageAllowingArchived(inv.getArgument(0), inv.getArgument(1)))
                .when(gateMock).loadVillageAllowingArchived(any(), any());
        lenient().doAnswer(inv -> real.findVisibleVillage(inv.getArgument(0), inv.getArgument(1)))
                .when(gateMock).findVisibleVillage(any(), any());

        return real;
    }
}
