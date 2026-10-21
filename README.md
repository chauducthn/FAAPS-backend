# FAAPS Backend

Spring Boot API using Java 21 and MySQL.

## Local development

Create a MySQL database named `faaps`. Set `DB_PASSWORD` and optionally `DB_URL` and `DB_USERNAME` as environment variables. See `.env.example` for example values; the application does not read `.env` files automatically.

The default JDBC URL is for local development and permits MySQL public key retrieval when TLS is disabled. For a remote or production database, set `DB_URL` to a TLS-enabled connection URL instead of using the local default.

```powershell
mvn spring-boot:run
```

The health endpoint is `http://localhost:8080/api/health`.

## Test APIs with Swagger UI

Start the backend, then open `http://localhost:8080/swagger-ui.html`. The generated OpenAPI JSON is at `http://localhost:8080/v3/api-docs`. Swagger UI and the API docs can be viewed without logging in; the Admin, Student, and Advisor APIs still require their respective roles.

To test Admin APIs in the same browser tab:

1. Run `GET /api/auth/csrf` using **Try it out**. This sets the CSRF cookie. Swagger UI is configured to copy the cookie into the `X-XSRF-TOKEN` header on subsequent requests.
2. Run `POST /api/auth/admin/login` with `{ "email": "admin", "password": "<your-admin-password>" }`. The browser retains the session cookie; do not put a real password in committed examples or screenshots.
3. Run `GET /api/auth/me` to confirm `role: ADMIN`, then try an Admin endpoint such as `GET /api/admin/majors`.
4. For POST, PATCH, PUT, or DELETE requests, ensure the CSRF cookie exists. Call `GET /api/auth/csrf` again if a request returns 403 after a new login. Use `POST /api/auth/logout` when finished.

For Student or Advisor APIs, sign in through the frontend with Google in the same browser profile, then open Swagger UI on `localhost:8080`. The account must have the required role. Swagger UI does not grant permissions; a 401 means no valid session and a 403 means missing role or CSRF token.

For the shared development RDS database and Google OAuth setup, follow [the team setup guide](docs/RDS_TEAM_SETUP.md). The launcher is `./scripts/Start-Rds.ps1`; it keeps database and OAuth secrets out of Git.

To start from a private environment file, copy `.env.example` to `.env`, replace the placeholder values, then run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-FromEnv.ps1` from the backend directory. The launcher loads `.env` for this process and configures the RDS TLS truststore. The application does not load `.env` by itself. Never commit or send a filled `.env` through Git or group chat; share secrets privately, and each developer should create their own `.env`.

User profiles do not store or expose avatar images. The `users.avatar_url` column and all S3 avatar integration were removed by the 2026-10-21 migration.

## Authentication API

The backend has session-based authentication, Google OAuth login, Admin password login, CSRF protection, role-based API access and logout. Students and Advisors use Google; Google first sign-in must create a Student through the account store. Admin accounts may use a password or a linked Google account.

Set `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` and `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` as environment variables to enable Google login. Configure the Google OAuth application with the authorized redirect URI `http://localhost:8080/login/oauth2/code/google`. Keep secrets outside Git. Without these variables, the backend starts with Google login disabled. `FAAPS_FRONTEND_URL` defaults to `http://localhost:5173` and controls the post-login redirect.

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/auth/csrf` | GET | Obtain the CSRF token and cookie before POST requests |
| `/oauth2/authorization/google` | GET | Start Google login when configured |
| `/api/auth/admin/login` | POST | Sign in an existing Admin with `{ "email": "...", "password": "..." }`; the initial Admin also accepts `admin` in the `email` field |
| `/api/auth/me` | GET | Read the authenticated account and role |
| `/api/auth/logout` | POST | Invalidate the session; send the CSRF header |

The browser must send cookies with requests. Send the `X-XSRF-TOKEN` header containing the value returned by `/api/auth/csrf` on login and logout requests. After Google login, the backend redirects to `<FAAPS_FRONTEND_URL>/auth/callback`; this frontend route should call `/api/auth/me`. A failed Google login redirects to `/login?error=...`. The API returns 401 when unauthenticated and 403 when the role is not allowed. Paths under `/api/admin/**`, `/api/advisor/**` and `/api/student/**` require the matching role; other API paths require authentication except the health and CSRF endpoints.

Admin password login uses `JdbcAuthAccountStore` when a datasource is available. The initial Admin is stored as `admin@faaps.local`; the alias `admin` is accepted by the login endpoint. The password is not seeded by application startup and must never be committed. `findById` is used on authenticated requests so suspended users lose access immediately.

Only the Admin referenced by the singleton `admin_control` row can log in or continue using an existing session. The current Admin may record a dated handover to another active Admin; this updates the singleton and revokes the old Admin's next request without deleting either account. Apply `sql/2026-10-21_single_active_admin_up.sql` before deploying this code. If several Admins are ACTIVE, explicitly set the initial Admin UUID in that script. New databases must create the initial Admin and singleton together.

Google account persistence uses Google's stable `sub`. A first-time Google user creates an active `users` row with the `STUDENT` role; the academic `students` row is created only after the required student code, major, curriculum and enrollment semester are available. An existing active Advisor account with a matching verified email and no password or Google link can be linked on first sign-in. Admin accounts are never auto-linked by email. Existing Google links are resolved by `sub`, not by email, and suspended accounts are denied. Never derive Advisor or Admin rights from the email domain.
