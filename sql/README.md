# FAAPS MySQL scripts

For a new local MySQL 8.4 database, run `faaps_schema_mysql84.sql` once. It creates the `faaps` database, the core tables including `advisor_work_schedules`, the two Admin AI report tables, and the three roles. It does not create an Admin password.

A local install is created by the Docker Compose stack, which runs the schema, `faaps_local_seed.sql`, `03-local-admin-and-defaults.sql` and `2026-11-28_seed_default_course_categories.sql` in order. That seed is deliberately clean: it creates the roles, the Admin account, the majors, the semesters, the curriculum versions, the eight course categories and the default academic rules, but **no sample courses, no demo advisors and no demo students**. The Admin imports the real course catalogue from FLM through Admin > Courses > Import, and creates the accounts through the app.

In MySQL Workbench, open each SQL file and execute the entire script. From a terminal with the MySQL client installed:

```powershell
mysql -u root -p --execute="SOURCE sql/faaps_schema_mysql84.sql"
mysql -u root -p --execute="SOURCE sql/faaps_local_seed.sql"
```

Run the schema only on a new local database. The shared RDS database already has its schema and demo rows; teammates connecting to that same RDS database should not import these files again. Keep database credentials outside Git.

For an existing database, run `2026-10-21_single_active_admin_up.sql` after the earlier migrations. With one ACTIVE Admin it selects that account. With several, replace `NULL` in the first line with the chosen current Admin UUID. The migration also removes `users.avatar_url` and renames `notifications.user_id` to `notifications.recipient_user_id`. Do not use MySQL's `--force` option: the preflight must stop execution on failure. The DOWN script removes the singleton, restores the nullable legacy avatar column, and renames the notification recipient column back; it restores access to every ACTIVE Admin and must not be run casually.

On the FAAPS RDS instance, run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File '.\scripts\Apply-Single-Admin-Rds.ps1'` from the backend directory and enter the master password only in PowerShell. If there are several ACTIVE Admins, pass `-CurrentAdminId '<chosen-admin-uuid>'`. This script verifies the database connection, refuses to overwrite an existing singleton, and confirms the avatar and notification column changes.

MySQL DDL commits implicitly: take a backup before applying the UP script and inspect the result before deploying the matching backend and frontend. On a new database, after creating the initial Admin account, initialize `admin_control.current_admin_id` with that account's ID. Verify it points to exactly one active Admin. Do not run the full schema against an existing database.

For the two supported course relation types, apply `2026-10-22_course_relation_types.sql` to an existing MySQL 8.4 database only after checking for unsupported rows. The migration stops without deleting data when either table contains an unsupported type; review those rows and decide their meaning rather than silently converting them. Use a backup and the MySQL client without `--force`; MySQL DDL is not transactional. The current DBML source is `sql/FAAPS_ERD_full.dbml`; the SQL schema file is not generated from it.

**Character set.** Always import SQL files that contain Vietnamese text with `--default-character-set=utf8mb4`, for example `mysql --default-character-set=utf8mb4 faaps < sql/file.sql` (in Windows PowerShell use `--execute="SOURCE sql/file.sql"` instead of `<`). A client that reads the file as latin1 stores "Vũ Thanh Lâm" as "VÅ© Thanh LÃ¢m". To repair a database that already has such names, review and run `2026-11-13_repair_double_encoded_names.sql` (it previews the change, only touches rows with the typical double-encoding marks, and is safe to run twice).

For mock data to test pagination, filters and bulk actions on a local database, run `2026-11-13_seed_mock_students_pagination.sql`. It adds 63 students (SE, AI and IA, cohorts K17-K19, about one in nine SUSPENDED) with Vietnamese names written as UTF-8 hex literals, so the result is correct whatever the client character set. It is idempotent. Do not run it on the shared RDS database.

To simplify advisor profiles in an existing database, review and back up advisor identifiers/specializations first, then apply `2026-10-22_simplify_advisor_profile.sql`. It copies existing `advisors.work_days` into `advisor_work_schedules` before removing `advisor_code`, `specialization`, and `work_days` from `advisors`. Advisor API identity is now the associated `users.email`. MySQL DDL commits implicitly; do not treat this migration as transactional. The DOWN script recreates the old columns but cannot recover the original advisor codes or specializations; it generates synthetic `LEGACY-<user_id>` codes, so use a database backup for a true restoration.

The current flow stores only the exact assignment start date in `advisors.assignment_start_date`; after backing up, apply `2026-10-26_remove_advisor_work_days.sql` to remove the obsolete work-day table created by the historical migration.

To add the default FLM course categories to an existing database, apply `2026-11-28_seed_default_course_categories.sql`. It is idempotent and only inserts missing rows. The local seed created only `CORE`, `MATH` and `SPEC`, but the browser import check rejects a course whose category is unknown (`Category "PREP" does not exist.`), so a FLM export cannot be imported without the remaining knowledge blocks: `PREP`, `GEN`, `FOUND`, `OJT` and `CAP`. `MATH` is kept because the seeded courses already point at it; `FOUND` is the FLM name for the same kind of block.
