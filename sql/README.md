# FAAPS MySQL scripts

For a new local MySQL 8.4 database, run `faaps_schema_mysql84.sql` once. It creates the `faaps` database, the 39 core tables, the two Admin AI report tables, and the three roles. It does not create an Admin password.

To add optional demo data, run `faaps_demo_seed.sql` after the schema. The demo seed is idempotent and creates one sample major, one curriculum, two advisors, four students, and two active advisor assignments. Demo accounts use `example.invalid` addresses and cannot sign in with Google.

In MySQL Workbench, open each SQL file and execute the entire script. From a terminal with the MySQL client installed:

```powershell
mysql -u root -p --execute="SOURCE sql/faaps_schema_mysql84.sql"
mysql -u root -p --execute="SOURCE sql/faaps_demo_seed.sql"
```

Run the schema only on a new local database. The shared RDS database already has its schema and demo rows; teammates connecting to that same RDS database should not import these files again. Keep database credentials outside Git.
