-- The scheduling flow records the exact assignment start date only.
-- Back up the database before applying because MySQL DDL commits implicitly.
DROP TABLE IF EXISTS advisor_work_schedules;

SELECT COUNT(*) AS advisors_with_start_date
FROM advisors WHERE assignment_start_date IS NOT NULL;
