package vn.edu.fpt.faaps.academic.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class CourseImportSheetParser {
    private static final String[] CODE_KEYS = {
            "subject code", "subjectcode", "course code", "code", "mã môn", "mã học phần"
    };
    private static final String[] NAME_KEYS = {
            "course name english", "course name", "name", "syllabus name", "tên môn", "tên học phần"
    };
    private static final String[] CREDITS_KEYS = {
            "nocredit", "no credit", "nocredits", "no credits", "credits", "credit", "tín chỉ", "số tín chỉ"
    };
    private static final String[] CATEGORY_KEYS = {
            "category", "category code", "course category", "department", "chuyên ngành"
    };
    private static final String[] KNOWLEDGE_BLOCK_KEYS = {
            "knowledge block", "knowledge_block", "khối kiến thức"
    };
    private static final String[] DESC_KEYS = { "description", "mô tả", "course description" };
    private static final String[] STATUS_KEYS = { "status", "course status", "active", "trạng thái" };
    private static final String[] LINK_KEYS = {
            "material link", "materiallink", "material_link", "materials", "link", "tài liệu"
    };
    private static final String[] PREREQ_KEYS = {
            "pre-requisite", "prerequisite", "prerequisites", "pre-requisites",
            "pre requisite", "tiên quyết", "môn tiên quyết"
    };
    private static final String[] OBJ_KEYS = { "objectives", "studenttasks", "student tasks", "mục tiêu" };
    private static final String[] LO_KEYS = { "learning outcomes", "learning_outcomes", "chuẩn đầu ra" };
    private static final String[] GRADING_KEYS = {
            "grading structure", "grading_structure", "assessments", "assessment", "cấu trúc điểm"
    };

    private CourseImportSheetParser() {
    }

    static List<RawCourseRow> parseSheet(Sheet sheet) {
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            return List.of();
        }
        HeaderMatch header = findHeaderRow(sheet, lastRow);
        return header != null ? parseTableRows(sheet, header, lastRow) : parseBlockRows(sheet, lastRow);
    }

    private static HeaderMatch findHeaderRow(Sheet sheet, int lastRow) {
        for (int r = 0; r <= Math.min(5, lastRow); r++) {
            Row rRow = sheet.getRow(r);
            if (rRow == null) {
                continue;
            }
            Map<String, Integer> candidateColMap = new HashMap<>();
            for (int c = 0; c < rRow.getLastCellNum(); c++) {
                Cell cell = rRow.getCell(c);
                if (cell != null) {
                    String key = cleanKey(getCellString(cell));
                    if (!key.isBlank()) {
                        candidateColMap.put(key, c);
                    }
                }
            }
            int codeCol = findCol(candidateColMap, CODE_KEYS);
            int nameCol = findCol(candidateColMap, NAME_KEYS);
            int creditsCol = findCol(candidateColMap, CREDITS_KEYS);
            if (codeCol != -1 && (nameCol != -1 || creditsCol != -1)) {
                return new HeaderMatch(r, codeCol, candidateColMap);
            }
        }
        return null;
    }

    private static List<RawCourseRow> parseTableRows(Sheet sheet, HeaderMatch header, int lastRow) {
        Map<String, Integer> colMap = header.colMap();
        int nameCol = findCol(colMap, NAME_KEYS);
        int creditsCol = findCol(colMap, CREDITS_KEYS);
        int catCol = findCol(colMap, CATEGORY_KEYS);
        int blockCol = findCol(colMap, KNOWLEDGE_BLOCK_KEYS);
        int descCol = findCol(colMap, DESC_KEYS);
        int statusCol = findCol(colMap, STATUS_KEYS);
        int linkCol = findCol(colMap, LINK_KEYS);
        int prereqCol = findCol(colMap, PREREQ_KEYS);
        int objCol = findCol(colMap, OBJ_KEYS);
        int loCol = findCol(colMap, LO_KEYS);
        int gradingCol = findCol(colMap, GRADING_KEYS);

        List<RawCourseRow> rows = new ArrayList<>();
        for (int r = header.rowNum() + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String code = getCellString(row.getCell(header.codeCol())).replaceAll("\\s+", "");
            String name = cellText(row, nameCol);
            if (code.isBlank() && name.isBlank()) continue;
            rows.add(new RawCourseRow(r, code, name, cellText(row, creditsCol), cellText(row, catCol),
                    cellText(row, descCol), cellText(row, prereqCol), cellText(row, objCol), cellText(row, loCol),
                    cellText(row, statusCol), cellText(row, linkCol), cellText(row, blockCol), cellText(row, gradingCol)));
        }
        return rows;
    }

    private static List<RawCourseRow> parseBlockRows(Sheet sheet, int lastRow) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Map<String, String> currentBlock = new HashMap<>();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String k = cleanKey(getCellString(row.getCell(0)));
            String v = getCellString(row.getCell(1));
            if (k.isBlank() && v.isBlank()) {
                if (!currentBlock.isEmpty()) {
                    blocks.add(currentBlock);
                    currentBlock = new HashMap<>();
                }
                continue;
            }
            boolean isNewCourseStart = !currentBlock.isEmpty() && (
                    (k.equals("syllabus id") && currentBlock.containsKey("syllabus id"))
                    || (k.equals("subject code") && currentBlock.containsKey("subject code"))
                    || (k.equals("code") && currentBlock.containsKey("code"))
                    || (k.equals("mã môn") && currentBlock.containsKey("mã môn")));
            if (isNewCourseStart) {
                blocks.add(currentBlock);
                currentBlock = new HashMap<>();
            }
            if (!k.isBlank()) {
                currentBlock.put(k, v);
            }
        }
        if (!currentBlock.isEmpty()) {
            blocks.add(currentBlock);
        }

        List<RawCourseRow> rows = new ArrayList<>();
        int blockIndex = 1;
        for (Map<String, String> block : blocks) {
            String code = findVal(block, CODE_KEYS);
            String name = findVal(block, NAME_KEYS);
            if (code.isBlank() && name.isBlank()) {
                continue;
            }
            rows.add(new RawCourseRow(blockIndex++, code, name, findVal(block, CREDITS_KEYS),
                    findVal(block, CATEGORY_KEYS), findVal(block, DESC_KEYS),
                    findVal(block, PREREQ_KEYS), findVal(block, OBJ_KEYS), findVal(block, LO_KEYS),
                    findVal(block, STATUS_KEYS), findVal(block, LINK_KEYS),
                    findVal(block, KNOWLEDGE_BLOCK_KEYS), findVal(block, GRADING_KEYS)));
        }
        return rows;
    }

    private static String cellText(Row row, int col) { return col != -1 ? getCellString(row.getCell(col)) : ""; }
    private static String cleanKey(String str) { return str == null ? "" : str.trim().toLowerCase().replaceAll("[:：]+$", "").trim(); }

    private static String findVal(Map<String, String> map, String... names) {
        for (String name : names) {
            String cleaned = cleanKey(name);
            if (map.containsKey(cleaned)) return map.get(cleaned).trim();
        }
        return "";
    }

    private static int findCol(Map<String, Integer> map, String... names) {
        for (String name : names) {
            if (map.containsKey(name.toLowerCase())) return map.get(name.toLowerCase());
        }
        return -1;
    }

    private static String getCellString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((long) cell.getNumericCellValue());
        if (cell.getCellType() == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return "";
    }

    private record HeaderMatch(int rowNum, int codeCol, Map<String, Integer> colMap) {}

    record RawCourseRow(int rowNumber, String code, String name, String creditsStr, String catStr,
            String description, String prerequisites, String objectives, String learningOutcomes,
            String statusStr, String materialLink, String knowledgeBlock, String gradingStructure) {}
}
