-- Student self-registration: the registration form collects the date of birth.
-- Apply once on every database (local, shared RDS). Safe to rerun on MySQL 8.0.29+ is not guaranteed, so run it once.
ALTER TABLE users ADD COLUMN date_of_birth DATE NULL AFTER phone;
