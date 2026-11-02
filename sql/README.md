# FAAPS MySQL scripts

For a new local MySQL 8.4 database, run `faaps_schema_mysql84.sql` once. It creates the `faaps` database, the core tables, the two Admin AI report tables, and the three roles. It does not create an Admin password.

To add realistic local test data, run `faaps_local_seed.sql` after the schema. The seed is idempotent and creates SE/IA programs, K17 curricula, real-looking course records, Vietnamese names, Student IDs in the `SE1#####`/`IA1#####` format, advisors, and assignments.

In MySQL Workbench, open each SQL file and execute the entire script. From a terminal with the MySQL client installed:

```powershell
mysql -u root -p --execute="SOURCE sql/faaps_schema_mysql84.sql"
mysql -u root -p --execute="SOURCE sql/faaps_local_seed.sql"
```

Run the schema only on a new local database. The shared RDS database already has its schema and demo rows; teammates connecting to that same RDS database should not import these files again. Keep database credentials outside Git.

The `2026-10-21_single_active_admin_up.sql` migration is historical and was used to create the former `admin_control` table. Do not apply it to a database already using `admin_account`. An older database still at that migration should next apply `2026-10-26_single_admin_account.sql`.

The `scripts/Apply-Single-Admin-Rds.ps1` helper also targets the historical `admin_control` migration; do not run it against the current schema.

The current schema uses `admin_account` with one row (`singleton_id = 1`) pointing to the sole active Admin. For an existing database, back it up and apply `2026-10-26_single_admin_account.sql` once. It refuses databases with zero or multiple Admin accounts, copies the existing Admin ID, then removes the former handover and control tables. MySQL DDL commits implicitly. Do not run the full schema against an existing database.

For the two supported course relation types, apply `2026-10-22_course_relation_types.sql` to an existing MySQL 8.4 database only after checking for unsupported rows. The migration stops without deleting data when either table contains an unsupported type; review those rows and decide their meaning rather than silently converting them. Use a backup and the MySQL client without `--force`; MySQL DDL is not transactional. The current DBML source is `sql/FAAPS_ERD_full.dbml`; the SQL schema file is not generated from it.

The historical `2026-10-22_simplify_advisor_profile.sql` created a separate work-day table. The current flow stores only the exact assignment start date in `advisors.assignment_start_date`; apply `2026-10-26_remove_advisor_work_days.sql` after a backup to remove the obsolete table.
