DROP TABLE admin_control;

SET @avatar_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'avatar_url'
);
SET @add_avatar_sql := IF(
  @avatar_column_exists = 0,
  'ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500) NULL AFTER phone',
  'SELECT ''Column avatar_url already exists'' AS info'
);
PREPARE add_avatar_statement FROM @add_avatar_sql;
EXECUTE add_avatar_statement;
DEALLOCATE PREPARE add_avatar_statement;

SET @notification_recipient_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'recipient_user_id'
);
SET @restore_notification_recipient_sql := IF(
  @notification_recipient_exists > 0,
  'ALTER TABLE notifications RENAME COLUMN recipient_user_id TO user_id',
  'SELECT ''Notification recipient column already restored'' AS info'
);
PREPARE restore_notification_recipient_statement FROM @restore_notification_recipient_sql;
EXECUTE restore_notification_recipient_statement;
DEALLOCATE PREPARE restore_notification_recipient_statement;
