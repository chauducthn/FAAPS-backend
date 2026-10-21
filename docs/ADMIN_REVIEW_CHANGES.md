# Admin review changes (2026-10-20)

- User Accounts is read-only. The list omits `createdAt`; the detail view still shows it. Avatar fields, APIs, S3 integration, and the `users.avatar_url` column were removed across DB, backend, and frontend.
- Multiple Admin accounts may exist, but only the Admin referenced by `admin_control.current_admin_id` can operate. A handover records the outgoing and incoming Admin, date, notes, and actor; it then changes the singleton and invalidates the old Admin session on the next request.
- Advisor schedule is set in Advisor Assignment Details. `GET/PUT /api/admin/advisors/{advisorId}/schedule` stores ISO weekdays (`1` Monday through `7` Sunday) and `assignmentStartDate`. The start date is recorded metadata; it does not retroactively alter existing assignments or block manual/automatic assignment.
- Apply `sql/2026-10-20_admin_handovers_advisor_schedule.sql`, then `sql/2026-10-21_single_active_admin_up.sql` to an existing MySQL database. The latter creates `admin_control`, renames the notification recipient column, and removes `users.avatar_url`. Do not apply the entire base schema to RDS.
