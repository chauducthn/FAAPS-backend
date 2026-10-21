# Admin API - phần BE của Thiện

Tất cả endpoint dưới đây yêu cầu phiên đăng nhập có role `ADMIN`. Request thay đổi dữ liệu cần gửi cookie phiên và CSRF token theo `/api/auth/csrf`.

## Tài khoản

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/users?role=STUDENT&status=ACTIVE&majorId=uuid&search=&page=0&size=20&sort=createdAt,desc` | Danh sách, lọc, phân trang; `majorId` tùy chọn, khi dùng chỉ trả tài khoản Student thuộc ngành đó |
| GET | `/api/admin/users/{id}` | Chi tiết tài khoản và thông tin học thuật hiện có |
| PATCH | `/api/admin/users/{id}/status` | Đổi trạng thái với body `{"status":"SUSPENDED"}` hoặc `ACTIVE` |
| POST | `/api/admin/users/status` | Bulk action với `{"userIds":["uuid"],"status":"SUSPENDED"}` |

User Accounts là read-only đối với thông tin hồ sơ. Hệ thống không lưu hoặc cung cấp ảnh đại diện; giao diện dùng chữ viết tắt từ tên người dùng.

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
| GET | `/api/admin/majors` | Danh sách `{id, code, name}` cho bộ lọc ngành |
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

## Ngành học (Majors)

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/majors/paged?cohort=&status=ACTIVE&search=&page=0&size=20&sort=createdAt,desc` | Danh sách ngành học, lọc theo cohort, trạng thái, phân trang |
| GET | `/api/admin/majors/{id}` | Chi tiết ngành học |
| POST | `/api/admin/majors` | Tạo ngành học mới: `{"code":"SE","name":"Software Engineering","totalCredits":140,"durationYears":4.0,"status":"ACTIVE","cohort":"K18"}` |
| PUT | `/api/admin/majors/{id}` | Cập nhật thông tin ngành học |
| DELETE | `/api/admin/majors/{id}` | Xóa ngành học (kiểm tra ràng buộc sinh viên và curriculum) |
| POST | `/api/admin/majors/bulk-delete` | Xóa hàng loạt: `{"ids":["uuid"]}` |

## Môn học (Courses)

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/courses?search=&status=ACTIVE&categoryId=&page=0&size=20&sort=code,asc` | Danh sách môn học |
| GET | `/api/admin/courses/categories` | Danh sách khối kiến thức/danh mục môn học |
| GET | `/api/admin/courses/{id}` | Chi tiết môn học và danh sách môn tiên quyết/song hành |
| POST | `/api/admin/courses` | Tạo môn học mới kèm môn tiên quyết |
| PUT | `/api/admin/courses/{id}` | Cập nhật môn học và môn tiên quyết |
| DELETE | `/api/admin/courses/{id}` | Xóa môn học (kiểm tra ràng buộc môn tiên quyết và curriculum) |
| POST | `/api/admin/courses/bulk-delete` | Xóa hàng loạt môn học |
| POST | `/api/admin/courses/import/preview` | Upload file Excel (.xlsx), kiểm tra schema, mã trùng, trả kết quả preview và lỗi |
| POST | `/api/admin/courses/import/confirm` | Xác nhận và lưu danh sách môn học hợp lệ vào DB |

## Quy chế học vụ (Academic Rules)

| Method | URL | Mục đích |
| --- | --- | --- |
| GET | `/api/admin/academic-rules?search=&status=ACTIVE&policyType=&curriculumVersionId=&page=0&size=20` | Danh sách quy chế học vụ |
| GET | `/api/admin/academic-rules/types` | Danh sách các loại quy chế hỗ trợ |
| GET | `/api/admin/academic-rules/{id}` | Chi tiết quy chế học vụ |
| POST | `/api/admin/academic-rules` | Tạo quy chế mới cho khung chương trình |
| PUT | `/api/admin/academic-rules/{id}` | Cập nhật quy chế học vụ |
| PATCH | `/api/admin/academic-rules/{id}/status` | Bật/tắt trạng thái hiệu lực: `{"isActive":true}` |
| DELETE | `/api/admin/academic-rules/{id}` | Xóa quy chế học vụ |
