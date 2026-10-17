# Shared RDS setup (development only)

The `faaps-dev` database has the FAAPS schema, including the advisor-assignment migration. The shared application user is `faaps_app`; it can read and change application data, but cannot create or alter tables. Never put the database password, Google Client Secret, or a credentials JSON file in Git or team chat.

## One-time setup per developer

1. Install Git, Java 21, Maven, and PowerShell 7. Clone the backend repository and check out the team branch: `git clone -b thiencdse173019 https://github.com/chauducthn/FAAPS-backend.git`.
2. Ask the AWS account owner to allow **your current public IP `/32`** for MySQL TCP port `3306` in the RDS security group. Do not open `0.0.0.0/0`. Repeat when your public IP changes.
3. Get the `faaps_app` password from the owner through a secure private channel. Do not use the RDS master password for application development.
4. In the backend directory, run `./scripts/Start-Rds.ps1` or fill a private `.env` based on `.env.example` and run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Start-FromEnv.ps1`. The launcher downloads AWS's public CA bundle, builds a local PKCS12 truststore, and starts Spring Boot with certificate and hostname verification. The CA bundle and truststore stay under `%LOCALAPPDATA%\FAAPS`, outside Git. Spring Boot does not read `.env` without the launcher.
5. Check `http://localhost:8080/api/health`. A healthy API returns `{"status":"ok","service":"faaps-api"}`. Stop the process with Ctrl+C.

If the connection times out, check the RDS IP rule and local firewall. If authentication fails, check the shared password. If certificate validation fails, remove `%LOCALAPPDATA%\FAAPS\rds-truststore-roots.p12` and rerun the script to rebuild it from the current AWS CA bundle.

Schema changes require a reviewed migration run by the database owner; the shared account deliberately has no DDL permissions. The database contains development data, including a bootstrap Admin account. Do not use the current Admin password in a deployed environment.

## Google OAuth for local development

Google Cloud project owner: create a Google Auth Platform OAuth client of type **Web application**. Set the authorized redirect URI exactly to `http://localhost:8080/login/oauth2/code/google`. Configure the app's audience as External for personal Google accounts; while in Testing, add each team member's Google account as a test user. Request only the identity scopes needed for sign-in (`openid`, `profile`, `email`).

Run `./scripts/Start-Rds.ps1 -EnableGoogle`. The script asks for the OAuth Client ID and Client Secret if they are not already in environment variables. Open `http://localhost:8080/oauth2/authorization/google` to start the redirect. Never place the Client Secret in `.env.example` or Git.

The backend resolves Google identities by `sub`, creates a `users` row for a new Student, and links an existing authorized Advisor by verified email. It does not automatically create a `students` academic profile because that table requires student code, major, curriculum and enrollment semester, which Google does not provide. Complete the profile when those values are available. The frontend still needs a login button and an `/auth/callback` page that calls `/api/auth/me` with the session cookie. Admin password login already works against RDS.
