package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * プレゼンス一括送信レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceBulkResponse {

    private final List<NotifiedTeam> notifiedTeams;
    private final List<SkippedTeam> skippedTeams;

    /**
     * 通知されたチーム情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class NotifiedTeam {
        private final Long teamId;
        private final String teamName;
        private final Long eventId;
    }

    /**
     * スキップされたチーム情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class SkippedTeam {
        private final Long teamId;
        private final String teamName;
    }
}
