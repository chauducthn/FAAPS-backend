-- Run once on an existing MySQL 8.4 database after a backup. DDL commits implicitly.
-- This migration intentionally removes the former handover history table.
DELIMITER $$
CREATE PROCEDURE verify_single_admin_account()
BEGIN
  IF (SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id
      WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE') <> 1
     OR (SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id
         WHERE r.code = 'ADMIN') <> 1
     OR (SELECT COUNT(*) FROM admin_control ac JOIN users u ON u.id = ac.current_admin_id
         JOIN roles r ON r.id = u.role_id
         WHERE ac.control_id = 1 AND r.code = 'ADMIN' AND u.status = 'ACTIVE') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Expected exactly one active ADMIN matching admin_control';
  END IF;
END$$
DELIMITER ;

CALL verify_single_admin_account();
DROP PROCEDURE verify_single_admin_account;

CREATE TABLE admin_account (
  singleton_id TINYINT PRIMARY KEY,
  user_id CHAR(36) NOT NULL UNIQUE,
  CONSTRAINT chk_admin_account_singleton CHECK (singleton_id = 1),
  CONSTRAINT fk_admin_account_user FOREIGN KEY (user_id) REFERENCES users(id)
);
INSERT INTO admin_account (singleton_id, user_id)
SELECT control_id, current_admin_id FROM admin_control WHERE control_id = 1;

DROP TABLE IF EXISTS admin_handovers;
DROP TABLE admin_control;

SELECT a.singleton_id, u.email, u.status
FROM admin_account a JOIN users u ON u.id = a.user_id;
