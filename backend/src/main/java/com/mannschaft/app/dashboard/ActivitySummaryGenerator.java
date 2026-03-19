package com.mannschaft.app.dashboard;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * アクティビティフィードの summary テキスト生成ユーティリティ。
 * activity_type に基づいて表示用サマリーを生成する。
 */
@Component
public class ActivitySummaryGenerator {

    private static final Map<ActivityType, String> SUMMARY_TEMPLATES = Map.of(
            ActivityType.POST_CREATED, "新しい投稿を作成しました",
            ActivityType.EVENT_CREATED, "新しいイベントを作成しました",
            ActivityType.MEMBER_JOINED, "メンバーとして参加しました",
            ActivityType.TODO_COMPLETED, "TODOを完了しました",
            ActivityType.BULLETIN_CREATED, "新しいスレッドを作成しました",
            ActivityType.POLL_CREATED, "新しいアンケートを作成しました",
            ActivityType.FILE_UPLOADED, "ファイルをアップロードしました"
    );

    /**
     * アクティビティ種別からサマリーテキストを生成する。
     */
    public String generate(ActivityType activityType) {
        return SUMMARY_TEMPLATES.getOrDefault(activityType, "アクティビティが記録されました");
    }
}
