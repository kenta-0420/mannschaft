package com.mannschaft.app.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 業務アラートサマリーレスポンス（F10.7）。
 * 認証済みユーザーが管理するチームごとの予約・問い合わせ未読件数をまとめて返す。
 */
@Getter
@Builder
public class AdminBusinessAlertSummaryResponse {

    private final Data data;

    @Getter
    @Builder
    public static class Data {
        private final List<TeamAlert> teams;
        private final int totalPending;
    }

    @Getter
    @Builder
    public static class TeamAlert {
        private final Long teamId;
        private final String teamName;
        private final boolean reservationModuleEnabled;
        private final Alerts alerts;
        private final Links links;
    }

    @Getter
    @Builder
    public static class Alerts {
        private final int newReservations;
        private final int pendingApproval;
        private final int unreadInquiries;
    }

    @Getter
    @Builder
    public static class Links {
        private final String reservationsUrl;
        /** 問い合わせチャンネル未設定の場合は null */
        private final String inquiryChannelUrl;
    }
}
