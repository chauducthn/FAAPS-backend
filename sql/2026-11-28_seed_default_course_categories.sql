-- Migration: Seed the default FLM course categories (idempotent for MySQL 8.4)
--
-- The local seed files only created CORE, MATH and SPEC. The FLM catalogue
-- (see faaps-ext/FAAPS-flm-exporter/parser.js and
-- faaps-doc/project/DATA_CONSTRAINTS_README.md) uses seven knowledge blocks:
--   PREP, GEN, FOUND, CORE, SPEC, OJT, CAP
-- The browser import check rejects any course whose category is unknown
-- ("Category \"PREP\" does not exist."), so the missing blocks have to exist
-- before a FLM export can be imported.
--
-- MATH is kept because seeded courses (MAE101, MAD101) already point at it.
-- FOUND is the FLM name for the same kind of block and is added alongside it.

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- Default course categories
-- -----------------------------------------------------------------------------
INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'PREP', 'Preparation Courses',
       'Pre-training preparation courses such as physical education, music, arts and life skills'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'PREP');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'GEN', 'General Education & Soft Skills',
       'Foreign languages, humanities, general education and soft skills requirements'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'GEN');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'FOUND', 'Foundation & Basic Sciences',
       'Foundational mathematics, computing and engineering science courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'FOUND');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'OJT', 'On-the-Job Training',
       'On-the-Job Training internship terms completed at a partner company'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'OJT');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CAP', 'Capstone Project',
       'Graduation capstone projects and thesis courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'CAP');

-- Make sure the three blocks that already exist keep a description, because the
-- browser import template and the admin course form show it.
UPDATE course_categories
SET description = 'Required foundational and professional core courses'
WHERE code = 'CORE' AND (description IS NULL OR TRIM(description) = '');

UPDATE course_categories
SET description = 'Foundational mathematics and quantitative reasoning courses'
WHERE code = 'MATH' AND (description IS NULL OR TRIM(description) = '');

UPDATE course_categories
SET description = 'Major specialization, software engineering, and graduation capstone courses'
WHERE code = 'SPEC' AND (description IS NULL OR TRIM(description) = '');

-- -----------------------------------------------------------------------------
-- Result
-- -----------------------------------------------------------------------------
SELECT code, name FROM course_categories ORDER BY code;
