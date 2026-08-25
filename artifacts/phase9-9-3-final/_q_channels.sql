SELECT publish_channel, COUNT(*) AS cnt
FROM published_item
WHERE status='PUBLISHED' AND (expires_at IS NULL OR expires_at>=CURRENT_TIMESTAMP)
GROUP BY publish_channel
ORDER BY publish_channel;
