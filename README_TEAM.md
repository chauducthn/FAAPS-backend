# FAAPS — Hướng dẫn môi trường cho nhóm

## 1. Môi trường AWS

| Thành phần | Địa chỉ / ghi chú |
| --- | --- |
| Frontend | [http://56.10.8.193](http://56.10.8.193) |
| API base URL | [http://56.10.8.193/api](http://56.10.8.193/api) |
| Database | AWS RDS; thao tác ghi trên môi trường này tác động vào dữ liệu dùng chung. |

Các địa chỉ và tên dịch vụ trên do nhóm cung cấp; hãy kiểm tra health endpoint và trạng thái EC2 trước khi demo. Không đưa mật khẩu RDS, Google OAuth hay file `.env` chứa bí mật lên Git. Tránh thử thao tác xóa/sửa dữ liệu quan trọng trên RDS chung.

## 2. Team Frontend — chạy local, dùng backend AWS

Không cần cài hoặc khởi động backend ở máy FE. Cài Node.js/npm rồi chạy:

```bash
git clone https://github.com/chauducthn/FAAPS-frontend.git
cd FAAPS-frontend
npm install --legacy-peer-deps
```

**Dùng `--legacy-peer-deps`** theo cấu hình hiện tại của nhóm để tránh xung đột peer dependencies. Tạo file `.env.local` tại gốc repo FE:

```dotenv
VITE_BACKEND_URL=http://56.10.8.193
VITE_API_URL=/api
```

Sau đó chạy `npm run dev` và mở `http://localhost:5173`. Vite chuyển tiếp `/api`, `/oauth2` và `/ws` đến EC2. Trình duyệt vẫn gọi `/api` trên `localhost:5173`, nên session cookie và CSRF của ứng dụng hoạt động cùng origin. Nếu đổi `.env.local`, hãy khởi động lại Vite.

Backend đã cho phép CORS từ `http://localhost:5173` và `http://56.10.8.193`. Có thể đặt `VITE_API_URL=http://56.10.8.193/api` để **gọi trực tiếp** các API không cần phiên đăng nhập, nhưng **không dùng cách này để test toàn bộ flow đăng nhập**: cookie `SameSite=Lax` không được gửi ổn định từ trang `localhost` tới API ở IP khác qua HTTP. Dùng proxy ở trên cho flow admin; Google OAuth còn cần redirect URI và `FAAPS_FRONTEND_URL` khớp môi trường, không thể bảo đảm chỉ bằng CORS.

## 3. Team Backend — cập nhật EC2

1. Review/test thay đổi, rồi push lên nhánh đang triển khai (`main` hoặc `master`).
2. SSH vào EC2 bằng tài khoản và SSH key của nhóm.
3. Kiểm tra repo trên EC2 có đúng nhánh triển khai, rồi chạy:

```bash
cd ~/FAAPS-backend
git pull
mvn clean package -DskipTests
sudo systemctl restart faaps-backend
sudo systemctl status faaps-backend --no-pager
```

Theo cấu hình deploy nhóm cung cấp, Nginx reverse-proxy tiền tố `/api` tới Spring Boot. Nếu service không lên, xem log:

```bash
sudo journalctl -u faaps-backend.service -n 50 -f
```

`-DskipTests` chỉ dùng khi cần build nhanh trên EC2; **chạy test trước khi push**. Kiểm tra tên service, đường dẫn repo và lệnh khởi động JAR của `systemd` trên EC2 trước lần deploy đầu tiên. Nếu có migration, áp dụng theo hướng dẫn riêng và sao lưu RDS trước khi restart. Hiện máy chủ dùng HTTP; nên bổ sung HTTPS trước khi dùng ngoài môi trường thử nghiệm.
