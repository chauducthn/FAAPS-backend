# Advisor assignment API

Student requests for an advisor are actionable notifications, not advising tickets.
`notifications.related_student_id` identifies the student and `resolved_at` is
set when an advisor is assigned. Reading a
notification does not resolve it. Actual academic advising stays in
`advising_requests` and requires an assigned advisor.

`cohort_advisors` stores the pool of advisors selected for a specific
`curriculum_versions.id` (major + cohort), including when there are no students
yet. Students who join later remain unassigned until admin runs auto assign.

## Database migration

For an existing MySQL database, run these additive migrations once and in order:
`sql/2026-10-17_cohort_advisors_notifications.sql`, then
`sql/2026-10-17_advisor_assignment_audit.sql`. New databases use
`sql/faaps_schema_mysql84.sql`. Do not rerun the full schema on existing data.

## Student endpoints

- `GET /api/student/advisor-assignment`: assigned advisor and whether an
  unresolved advisor request exists.
- `POST /api/student/advisor-assignment-requests`: idempotent notification to
  admins. A student with an advisor cannot use it.
- `POST /api/student/advising-requests`: `{ "title": "...", "description": "..." }`.
  Requires an assigned advisor; sends an academic ticket to that advisor.

## Admin endpoints

- `GET /api/admin/assignments/summary`: total, assigned, unassigned,
  `pendingAdvisorRequests`, and counts by major-specific cohort.
- `GET /api/admin/assignments/unassigned?cohortId=UUID&search=...&hasPendingRequest=true&page=0&size=20`:
  server-paginated unassigned students; each row has `hasPendingRequest`.
- `GET /api/admin/assignments/advisors/by-cohort?cohortId=UUID&search=...&page=0&size=20`:
  server-paginated advisor/cohort counts, including advisor contact/status fields.
- `GET /api/admin/assignments/cohorts/{cohortId}/advisors`: configured advisor
  pool, even when the cohort has no students.
- `PUT /api/admin/assignments/cohorts/{cohortId}/advisors`:
  `{ "advisorIds": ["UUID", "UUID"] }`. Replaces the active pool; `[]` clears it.
- `GET /api/admin/assignments/students?cohortId=UUID&page=0&size=20&search=...`:
  all students for that major-specific curriculum version (assigned and unassigned),
  including `majorCode`, `advisorId`, `advisorName`, and `assignedDate`.
- `GET /api/admin/assignments/advisors/{advisorId}` includes advisor email,
  status, assigned students, all cohorts, and configured cohorts.
- `GET /api/admin/assignments/students/{studentId}/history` includes advisor
  code, assigning admin, exact assignment time, and notes.
- `POST /api/admin/assignments/cohorts/{cohortId}/preview` and `/assign`:
  `{ "advisorId": "UUID" }`. Assigns only unassigned students; `/assign` also
  includes the selected advisor in the cohort pool.
- `POST /api/admin/assignments/auto-assign/cohort/preview` and `/confirm`:
  `{ "cohortId": "UUID" }`. Uses all active advisors in the stored pool; an
  optional `advisorIds` subset can narrow it. It balances final total loads
  and returns `totalBefore`, `totalAfter`, and capacity warnings. A cohort without
  a configured pool returns `COHORT_ADVISORS_NOT_CONFIGURED`.
- `POST /api/admin/assignments/assign/preview`:
  `{ "studentIds": ["UUID"], "advisorId": "UUID" }`. Returns future scheduled
  appointments affected by reassignment.
- `POST /api/admin/assignments/assign`: same body, plus
  `"acknowledgeAppointmentCancellation": true` if preview reported
  appointments. Without acknowledgement, API returns HTTP 409 and does not
  change the assignment. Confirming reassigns and cancels those appointments.
- `PATCH /api/admin/assignments/advisors/{id}/limit`:
  `{ "maxAdvisees": 30 }`. Limits produce warnings, not hard blocks.
- `GET /api/admin/notifications?unreadOnly=true&page=0&size=20` returns a page
  that includes `studentCode` and `cohortId`. `PATCH /api/admin/notifications/{id}/read`
  marks one read; `PATCH /api/admin/notifications/read-all` marks all the
  current admin's notifications read. Pending request count is independent of read state.
- Admin clients connect to `/ws` using their authenticated HTTP session and
  subscribe to `/user/queue/admin/notifications`. Only the current authenticated Admin can
  subscribe; new advisor requests publish an `ADVISOR_REQUEST` event after the
  database transaction commits. The event contains `studentCode`, `cohortId`,
  and `createdAt`. On reconnect, reload `GET /api/admin/notifications` because
  the in-process broker does not retain missed events or share them between
  multiple backend instances.

Notifications use `recipient_user_id` for their destination account. User and advisor responses do not contain avatar fields; the frontend renders initials instead.

API errors use `{ "success": false, "code": "...", "message": "..." }`.
Conflicts distinguish `ADVISOR_INACTIVE`, `ADVISOR_NOT_IN_COHORT`, and
`COHORT_ADVISORS_NOT_CONFIGURED`.

The legacy version-string auto-assignment endpoints remain for compatibility,
but new screens should use `cohortId` so equal labels in different majors never
mix students.
