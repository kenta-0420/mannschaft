-- F08.7.1 隊0: chat_channels.channel_type の桁拡張（VARCHAR(20) → VARCHAR(30)）
-- 後続（隊1）で `TOURNAMENT_DIVISION_CHAT`（24字）を ChannelType enum に追加する予定だが、
-- 現状 VARCHAR(20)（V4.013）では桁あふれするため、桁のみ先行して拡張する。
-- この波では enum 値（TOURNAMENT_CHAT 等）は追加しない（隊1 の担当）。
-- 既存の NOT NULL 制約・DEFAULT（なし）を維持する。
ALTER TABLE chat_channels MODIFY channel_type VARCHAR(30) NOT NULL;
