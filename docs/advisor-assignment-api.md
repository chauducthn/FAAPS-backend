# Advisor assignment API

Student requests for an advisor are actionable notifications, not advising tickets.
`notifications.related_student_id` identifies the student and `resolved_at` is
set when an advisor is assigned or the request is withdrawn. Reading a
notification does not resolve it. Actual academic advising stays in
`advising_requests` and requires an assigned advisor.

`cohort_advisors` stores the pool of advisors selected for a specific
`curriculum_versions.id` (major + cohort), including when there are no students
yet. Students who join later remain unassigned until admin runs auto assign.

## Database migration

For an existing MySQL database, run **only**
`sql/2026-10-17_cohort_advisors_notifications.sql` once. New databases use
`sql/faaps_schema_mysql84.sql`. Do not rerun the full schema on existing data.

## Student endpoints

- `GET /api/student/advisor-assignment`: assigned advisor and whether an
  unresolved advisor request exists.
- `POST /api/student/advisor-assignment-requests`: idempotent notification to
  admins. A student with an advisor cannot use it.
- `DELETE /api/student/advisor-assignment-requests/{id}`: withdraw the student's
  unresolved notification.
- `POST /api/student/advising-requests`: `{ "title": "...", "description": "..." }`.
  Requires an assigned advisor; sends an academic ticket to that advisor.

## Admin endpoints

- `GET /api/admin/assignments/summary`: total, assigned, unassigned,
  `pendingAdvisorRequests`, and counts by major-specific cohort.
- `GET /api/admin/assignments/unassigned?cohortId=UUID`: students without an
  advisor across all cohorts by default; each row has `hasPendingRequest`.
- `GET /api/admin/assignments/advisors/by-cohort`: advisor/cohort student counts.
- `GET /api/admin/assignments/cohorts/{cohortId}/advisors`: configured advisor
  pool, even when the cohort has no students.
- `PUT /api/admin/assignments/cohorts/{cohortId}/advisors`:
  `{ "advisorIds": ["UUID", "UUID"] }`. Replaces the active pool; `[]` clears it.
- `POST /api/admin/assignments/cohorts/{cohortId}/preview` and `/assign`:
  `{ "advisorId": "UUID" }`. Assigns only unassigned students; `/assign` also
  includes the selected advisor in the cohort pool.
- `POST /api/admin/assignments/auto-assign/cohort/preview` and `/confirm`:
  `{ "cohortId": "UUID" }`. Uses all active advisors in the stored pool; an
  optional `advisorIds` subset can narrow it. It balances final total loads
  and returns capacity warnings. Confirm recalculates under student locks.
- `POST /api/admin/assignments/assign/preview`:
  `{ "studentIds": ["UUID"], "advisorId": "UUID" }`. Returns future scheduled
  appointments affected by reassignment.
- `POST /api/admin/assignments/assign`: same body, plus
  `"acknowledgeAppointmentCancellation": true` if preview reported
  appointments. Without acknowledgement, API returns HTTP 409 and does not
  change the assignment. Confirming reassigns and cancels those appointments.
- `PATCH /api/admin/assignments/advisors/{id}/limit`:
  `{ "maxAdvisees": 30 }`. Limits produce warnings, not hard blocks.
- `GET /api/admin/notifications?unreadOnly=true` and
  `PATCH /api/admin/notifications/{id}/read`: each admin sees and reads their
  own notifications. The pending request count is independent of read state.

The legacy version-string auto-assignment endpoints remain for compatibility,
but new screens should use `cohortId` so equal labels in different majors never
mix students.
