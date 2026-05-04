CREATE OR REPLACE VIEW v_announcement_reads_by_person AS
SELECT
    id,
    announcement_feed_id,
    user_id,
    read_at
FROM announcement_read_status
WHERE is_proxy_confirmed = 0;
