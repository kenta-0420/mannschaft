package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1（チーム加盟の双方向化とチームグループ）§11 エラーコードの契約テスト（試練・AC-G118 BE 側）。
 *
 * <p>設計書 §11 の各コードについて、(1) enum 定数が存在する、(2) コード文字列が定数名と一致する、
 * (3) メッセージ（ja）が §11 のとおり、(4) {@code BusinessException} 経由で
 * {@link GlobalExceptionHandler#resolveStatus(ErrorCode)} が §11 の HTTP ステータスを返す、を固定する。
 * 未実装のコードは {@code Enum.valueOf} を実行時に解決するため、コンパイルは通り、実行時に red になる。</p>
 */
@DisplayName("F01.2.1 §11 エラーコードと HTTP ステータスの契約（AC-G118）")
class OrgTeamGroupErrorCodeContractTest {

    private static final String ORG = "com.mannschaft.app.organization.OrgErrorCode";
    private static final String TEAM = "com.mannschaft.app.team.TeamErrorCode";
    private static final String BROADCAST = "com.mannschaft.app.social.announcement.AnnouncementErrorCode";

    /** 設計書 §11 の表そのまま（コード → enum クラス・HTTP・ja メッセージ）。 */
    private record Spec(String enumClass, String code, HttpStatus status, String messageJa) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec(ORG, "ORG_064", HttpStatus.NOT_FOUND, "チームグループが見つかりません"),
            new Spec(ORG, "ORG_065", HttpStatus.CONFLICT, "同じ名前のチームグループがすでにあります"),
            new Spec(ORG, "ORG_066", HttpStatus.UNPROCESSABLE_ENTITY, "チームグループは1組織あたり200件までです"),
            new Spec(ORG, "ORG_067", HttpStatus.CONFLICT, "この組織ではチームグループ機能が無効です"),
            new Spec(ORG, "ORG_068", HttpStatus.CONFLICT, "チームグループの構成が変わっています。画面を更新してください"),
            new Spec(ORG, "ORG_069", HttpStatus.BAD_REQUEST, "この組織に加盟していないチームが含まれています"),
            new Spec(ORG, "ORG_070", HttpStatus.UNPROCESSABLE_ENTITY,
                    "グループ選択を必須にするには、グループ機能を有効にしてグループを1件以上作成してください"),
            new Spec(TEAM, "TEAM_064", HttpStatus.FORBIDDEN, "この組織はチームからの加盟申請を受け付けていません"),
            new Spec(TEAM, "TEAM_065", HttpStatus.CONFLICT, "このチームはすでにこの組織に加盟しています"),
            new Spec(TEAM, "TEAM_066", HttpStatus.CONFLICT, "このチームとこの組織の間には、処理中の申請または招待があります"),
            new Spec(TEAM, "TEAM_067", HttpStatus.BAD_REQUEST, "この組織への申請にはチームグループの選択が必要です"),
            new Spec(TEAM, "TEAM_068", HttpStatus.FORBIDDEN, "現在この組織には申請（招待）できません"),
            new Spec(TEAM, "TEAM_069", HttpStatus.UNPROCESSABLE_ENTITY, "同時に申請できる組織は10件までです"),
            new Spec(TEAM, "TEAM_070", HttpStatus.NOT_FOUND, "申請・招待・加盟が見つかりません"),
            new Spec(TEAM, "TEAM_071", HttpStatus.CONFLICT, "この申請・招待はすでに処理されています"),
            new Spec(TEAM, "TEAM_072", HttpStatus.BAD_REQUEST, "指定したチームグループは選択できません"),
            new Spec(BROADCAST, "BROADCAST_006", HttpStatus.BAD_REQUEST, "指定したチームグループは選択できません"),
            new Spec(BROADCAST, "BROADCAST_007", HttpStatus.BAD_REQUEST, "この組織ではチームグループ機能が無効です"),
            new Spec(BROADCAST, "BROADCAST_008", HttpStatus.BAD_REQUEST, "範囲の指定が正しくありません（開始が終了より後ろです）"),
            new Spec(BROADCAST, "BROADCAST_009", HttpStatus.BAD_REQUEST, "対象になる人がいません"),
            new Spec(BROADCAST, "BROADCAST_010", HttpStatus.BAD_REQUEST,
                    "個別に選べるチームは5,000までです。「すべてのチーム」かチームグループを使ってください"),
            new Spec(BROADCAST, "BROADCAST_011", HttpStatus.BAD_REQUEST, "チームの個別指定とチームグループ指定は同時に使えません"),
            new Spec(BROADCAST, "BROADCAST_012", HttpStatus.BAD_REQUEST, "チームの告知ではチームグループを指定できません"),
            new Spec(BROADCAST, "BROADCAST_013", HttpStatus.BAD_REQUEST,
                    "テンプレートの範囲に削除されたチームグループが含まれています。範囲を選び直してください"));

    @Test
    @DisplayName("§11 の全24コードが仕様表に載っている（空虚 green 防止）")
    void 仕様表の件数() {
        assertThat(SPECS).hasSize(7 + 9 + 8);
    }

    @Test
    @DisplayName("§11 の各コードの enum 定数が存在し、コード文字列が定数名と一致する")
    void 定数が存在する() {
        List<String> problems = new ArrayList<>();
        for (Spec spec : SPECS) {
            ErrorCode ec = resolve(spec, problems);
            if (ec != null && !spec.code().equals(ec.getCode())) {
                problems.add(spec.code() + ": getCode()=" + ec.getCode());
            }
        }
        assertThat(problems).as("未実装または不整合のエラーコード: %s", problems).isEmpty();
    }

    @Test
    @DisplayName("§11 の各コードが BusinessException 経由で規定の HTTP ステータスになる")
    void HTTPステータスが仕様どおり() {
        Map<String, String> mismatches = new LinkedHashMap<>();
        for (Spec spec : SPECS) {
            List<String> problems = new ArrayList<>();
            ErrorCode ec = resolve(spec, problems);
            if (ec == null) {
                mismatches.put(spec.code(), problems.get(0));
                continue;
            }
            BusinessException ex = new BusinessException(ec);
            HttpStatus actual = GlobalExceptionHandler.resolveStatus(ex.getErrorCode());
            if (actual != spec.status()) {
                mismatches.put(spec.code(), "期待 " + spec.status() + " / 実際 " + actual);
            }
        }
        assertThat(mismatches).as("HTTP ステータスが §11 と一致しないコード: %s", mismatches).isEmpty();
    }

    @Test
    @DisplayName("§11 の各コードのメッセージ（ja）が仕様どおりで、Severity が ERROR（=既定500）でない")
    void メッセージとSeverity() {
        List<String> problems = new ArrayList<>();
        for (Spec spec : SPECS) {
            ErrorCode ec = resolve(spec, problems);
            if (ec == null) {
                continue;
            }
            if (!spec.messageJa().equals(ec.getMessage())) {
                problems.add(spec.code() + ": message=" + ec.getMessage());
            }
            if (ec.getSeverity() == ErrorCode.Severity.ERROR) {
                problems.add(spec.code() + ": Severity.ERROR は既定 500 になる（クライアント起因には付けない）");
            }
        }
        assertThat(problems).as("メッセージ・Severity の不整合: %s", problems).isEmpty();
    }

    @Test
    @DisplayName("自己検証: 存在しないコードは resolve が問題として報告する（偽陰性防止）")
    void 自己検証_未実装検出() {
        List<String> problems = new ArrayList<>();
        ErrorCode ec = resolve(new Spec(ORG, "ORG_999", HttpStatus.NOT_FOUND, "x"), problems);
        assertThat(ec).isNull();
        assertThat(problems).hasSize(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ErrorCode resolve(Spec spec, List<String> problems) {
        try {
            Class<?> cls = Class.forName(spec.enumClass());
            return (ErrorCode) Enum.valueOf((Class<? extends Enum>) cls, spec.code());
        } catch (IllegalArgumentException | ClassNotFoundException e) {
            problems.add(spec.code() + ": 未実装（" + spec.enumClass() + " に定数が無い）");
            return null;
        }
    }
}
