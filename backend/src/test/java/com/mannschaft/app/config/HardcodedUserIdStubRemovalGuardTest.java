package com.mannschaft.app.config;

import com.mannschaft.app.facility.controller.TeamFacilityBookingController;
import com.mannschaft.app.proxyvote.controller.ProxyVoteExportController;
import com.mannschaft.app.ticket.controller.MyTicketController;
import com.mannschaft.app.ticket.controller.TicketBookController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 認可根治戦役 束A（Wave 0）番人テスト: {@code private Long getCurrentUserId() { return 1L; }}
 * スタブが 4 コントローラから撤去され、{@code SecurityUtils.getCurrentUserId()} 直接呼び出しへ
 * 置換されたことを機械的に担保する。
 *
 * <p>スタブが残っていると、認証済みの誰が叩いても常に userId=1 として扱われる BOLA/なりすまし
 * 脆弱性になる。除去後は当該コントローラに {@code getCurrentUserId} メソッドが宣言されていないこと。</p>
 */
@DisplayName("束A: return 1L スタブ撤去の番人")
class HardcodedUserIdStubRemovalGuardTest {

    @ParameterizedTest(name = "{0} に getCurrentUserId スタブが残っていない")
    @ValueSource(classes = {
            TeamFacilityBookingController.class,
            TicketBookController.class,
            MyTicketController.class,
            ProxyVoteExportController.class
    })
    @DisplayName("4 コントローラに getCurrentUserId() スタブが宣言されていない")
    void controllers_have_no_hardcoded_getCurrentUserId_stub(Class<?> controllerClass) {
        boolean hasStub = false;
        try {
            controllerClass.getDeclaredMethod("getCurrentUserId");
            hasStub = true;
        } catch (NoSuchMethodException expected) {
            // 撤去済み。SecurityUtils.getCurrentUserId() へ置換されている。
        }
        if (hasStub) {
            throw new AssertionError(
                    controllerClass.getSimpleName()
                            + " に return 1L スタブ getCurrentUserId() が残存している。"
                            + " SecurityUtils.getCurrentUserId() へ置換すること。");
        }
    }
}
