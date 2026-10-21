SET @initial_admin_id = NULL;

DROP PROCEDURE IF EXISTS check_single_admin_migration;
DELIMITER //
CREATE PROCEDURE check_single_admin_migration()
BEGIN
  DECLARE active_admins INT;
  DECLARE notification_user_columns INT;
  SELECT COUNT(*) INTO active_admins
  FROM users u JOIN roles r ON r.id = u.role_id
  WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE';
  IF @initial_admin_id IS NULL AND active_admins <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Set @initial_admin_id to the current ACTIVE admin UUID before migrating';
  END IF;
  IF @initial_admin_id IS NULL THEN
    SELECT u.id INTO @initial_admin_id FROM users u JOIN roles r ON r.id = u.role_id
    WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE';
  END IF;
  SELECT COUNT(*) INTO active_admins FROM users u JOIN roles r ON r.id = u.role_id
  WHERE u.id = @initial_admin_id AND r.code = 'ADMIN' AND u.status = 'ACTIVE';
  IF active_admins <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'The chosen initial admin is not an ACTIVE admin';
  END IF;
  SELECT COUNT(*) INTO notification_user_columns
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notifications'
    AND column_name IN ('user_id', 'recipient_user_id');
  IF notification_user_columns <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'notifications must contain exactly one recipient column';
  END IF;
END//
DELIMITER ;

CALL check_single_admin_migration();
DROP PROCEDURE check_single_admin_migration;

SET @avatar_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'avatar_url'
);
SET @drop_avatar_sql := IF(
  @avatar_column_exists > 0,
  'ALTER TABLE users DROP COLUMN avatar_url',
  'SELECT ''Column avatar_url already dropped'' AS info'
);
PREPARE drop_avatar_statement FROM @drop_avatar_sql;
EXECUTE drop_avatar_statement;
DEALLOCATE PREPARE drop_avatar_statement;

SET @notification_user_id_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'user_id'
);
SET @rename_notification_recipient_sql := IF(
  @notification_user_id_exists > 0,
  'ALTER TABLE notifications RENAME COLUMN user_id TO recipient_user_id',
  'SELECT ''Notification recipient column already normalized'' AS info'
);
PREPARE rename_notification_recipient_statement FROM @rename_notification_recipient_sql;
EXECUTE rename_notification_recipient_statement;
DEALLOCATE PREPARE rename_notification_recipient_statement;

CREATE TABLE admin_control (
  control_id TINYINT PRIMARY KEY,
  current_admin_id CHAR(36) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_admin_control_singleton CHECK (control_id = 1),
  CONSTRAINT fk_admin_control_current FOREIGN KEY (current_admin_id) REFERENCES users(id)
);

INSERT INTO admin_control (control_id, current_admin_id) VALUES (1, @initial_admin_id);
