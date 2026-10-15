# Admin API - phần BE của Thiện

Tất cả endpoint dưới đây yêu cầu phiên đăng nhập có role `ADMIN`. Request thay đổi dữ liệu cần gửi cookie phiên và CSRF token theo `/api/auth/csrf`.

## Tài khoản

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/users?role=STUDENT&status=ACTIVE&search=&page=0&size=20&sort=createdAt,desc` | Danh sách, lọc, phân trang |
| GET | `/api/admin/users/{id}` | Chi tiết tài khoản và thông tin học thuật hiện có |
| PATCH | `/api/admin/users/{id}` | Sửa `fullName`, `phone`, `avatarUrl` |
| PATCH | `/api/admin/users/{id}/status` | Đổi trạng thái với body `{"status":"SUSPENDED"}` hoặc `ACTIVE` |
| POST | `/api/admin/users/status` | Bulk action với `{"userIds":["uuid"],"status":"SUSPENDED"}` |

`avatarUrl` hiện nhận URL HTTPS. Upload ảnh nhị phân/S3 cần cấu hình kho lưu trữ trước khi FE dùng nút chọn file.

## Phân công advisor

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/assignments/advisors` | Advisor và số sinh viên đang phụ trách |
| GET | `/api/admin/assignments/advisors/{advisorId}` | Chi tiết advisor và sinh viên hiện tại |
| GET | `/api/admin/assignments/cohorts` | Danh sách cohort lấy từ `curriculum_versions.version` |
| GET | `/api/admin/assignments/students?cohort=K17&unassignedOnly=true` | Sinh viên theo cohort |
| GET | `/api/admin/assignments/students/{studentId}/history` | Lịch sử advisor của sinh viên |
| POST | `/api/admin/assignments/assign` | Gán/chuyển advisor: `{"studentIds":["uuid"],"advisorId":"uuid"}` |
| POST | `/api/admin/assignments/auto-assign/preview` | Xem phân bổ dự kiến: `{"cohort":"K17","advisorIds":["uuid"]}` |
| POST | `/api/admin/assignments/auto-assign/confirm` | Xác nhận phân bổ trên dữ liệu hiện tại |

Preview không giữ chỗ. Confirm khóa sinh viên và advisor, chỉ gán sinh viên chưa có advisor. Chuyển advisor lưu lịch sử, chuyển open advising requests, hủy future scheduled sessions của advisor cũ và thông báo cho sinh viên.

## Dashboard và appointment

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/dashboard/accounts` | Số Student, Advisor và cohort |
| GET | `/api/admin/dashboard/new-students?startMonth=2025-07&endMonth=2026-06&majorId=uuid` | Số tài khoản Student mới theo tháng; `majorId` tùy chọn |
| GET | `/api/admin/appointments?status=SCHEDULED&search=&page=0&size=20` | Danh sách appointment |
| GET | `/api/admin/appointments/summary` | Tổng số theo trạng thái |
| GET | `/api/admin/appointments/{id}` | Chi tiết appointment chỉ đọc |

Thống kê appointment hiện đọc từ bảng `advising_sessions`. Bảng này dùng trạng thái `SCHEDULED`, chưa phải mô hình booking 30 phút đã chốt.

## AI reports

Trước khi gọi các endpoint AI report, DBA cần chạy `sql/2026-10-15_admin_ai_reports.sql` trên DB `faaps` bằng tài khoản có quyền tạo bảng. Migration tạo một ticket cho một hội thoại và nhiều response bị báo cáo trong ticket.

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/ai-reports?status=PENDING&search=&page=0&size=20` | Danh sách report; dashboard gọi với `PENDING` |
| GET | `/api/admin/ai-reports/{id}` | Report, toàn bộ hội thoại, cờ response bị báo cáo và nguồn RAG |
| POST | `/api/admin/ai-reports/{id}/resolve` | Body `{"status":"RESOLVED","resolutionNote":"..."}`; cũng hỗ trợ `DISMISSED` |

Các list API trả `items`, `page`, `size`, `totalElements`, `totalPages`. Dữ liệu mới do Student tạo ticket là phần API Student, chưa nằm trong các endpoint Admin này.
