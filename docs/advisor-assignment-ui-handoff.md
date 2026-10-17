# Advisor assignment UI handoff

- Admin Assignment overview: total/assigned/unassigned students, pending advisor-request count, counts by major and cohort; the notification bell links here. No separate request-management page.
- Advisor list/detail: active students by cohort, configured cohort pool (including zero-student cohorts), and editable advisory limit.
- Cohort assignment modal: choose one advisor to take **only currently unassigned** students of that cohort; show preview count and over-limit warning. Configuring several advisors for a cohort before students join is a separate action.
- Auto-assign: select a cohort, preview the distribution across its configured advisors using their existing loads, show any limit warning, then confirm; new students remain unassigned until admin runs it again.
- Unassigned students: list across all cohorts with `hasPendingRequest`, cohort filter, single/bulk assignment and reassignment. Before changing advisor, show affected future appointments; confirmation cancels those appointments.
- Student without advisor: show “You have not been assigned an advisor yet. Request an advisor to get support.” with request/withdraw actions. This only creates admin notifications, not an academic advising ticket.
- Student with advisor: show advisor details and a separate academic advising-request form. Admin notification read/unread does not change the pending request count; actual assignment resolves it.
