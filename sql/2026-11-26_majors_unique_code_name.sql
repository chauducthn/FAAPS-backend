-- Allow multiple major names under the same major code while keeping (code, name) unique.
ALTER TABLE majors
    DROP INDEX code,
    ADD UNIQUE KEY uq_majors_code_name (code, name),
    ADD INDEX idx_majors_code (code);
