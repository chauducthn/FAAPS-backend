-- =============================================================================
-- FAAPS repair: names that show as "VÅ© Thanh LÃ¢m" instead of "Vũ Thanh Lâm"
-- Date: 2026-11-13
--
-- Cause: a SQL file with Vietnamese text was imported with a client that read it as latin1 (cp1252), for example
-- `mysql < file.sql` in Windows PowerShell without --default-character-set=utf8mb4. Every UTF-8 byte was then
-- stored as its own character, so one letter became two or three.
--
-- This script turns such text back into the original. It only touches rows that contain the typical
-- double-encoding marks (Ã, Å, Ä, Æ, á» or áº), so correct rows are left alone and a second run changes nothing.
--
-- How to run (always with an explicit character set):
--   mysql --default-character-set=utf8mb4 faaps < sql/2026-11-13_repair_double_encoded_names.sql
-- Look at the preview first. Run it on the database you mean to repair, after taking a backup.
-- Add another table/column to section 2 if its text is broken too.
-- =============================================================================
USE `faaps`;
SET NAMES utf8mb4;

-- 1. Preview: the text now, and the text after the repair.
SELECT `email`,
       `full_name` AS broken,
       CONVERT(CAST(CONVERT(`full_name` USING latin1) AS BINARY) USING utf8mb4) AS repaired
FROM `users`
WHERE `full_name` COLLATE utf8mb4_bin REGEXP 'Ã|Å|Ä|Æ|á»|áº';

-- 2. Repair. A row whose bytes cannot be read back as UTF-8 makes the statement fail without changing anything.
UPDATE `users`
SET `full_name` = CONVERT(CAST(CONVERT(`full_name` USING latin1) AS BINARY) USING utf8mb4),
    `updated_at` = CURRENT_TIMESTAMP
WHERE `full_name` COLLATE utf8mb4_bin REGEXP 'Ã|Å|Ä|Æ|á»|áº';

-- 3. Check: this must return 0 rows.
SELECT `email`, `full_name`
FROM `users`
WHERE `full_name` COLLATE utf8mb4_bin REGEXP 'Ã|Å|Ä|Æ|á»|áº';
