package vn.edu.fpt.faaps.academic.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

final class AttendanceFileParser {
    private AttendanceFileParser() {
    }

    static List<String[]> readLines(String fileName, InputStream in) throws IOException {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? readCsvLines(in)
                : readExcelLines(in);
    }

    static void validateHeaders(String[] header, List<String> expectedHeaders) {
        String expectedMessage = "The file must start with the columns: "
                + String.join(", ", expectedHeaders) + ".";
        if (header.length < expectedHeaders.size()) {
            throw new IllegalArgumentException(expectedMessage);
        }
        for (int i = 0; i < expectedHeaders.size(); i++) {
            String actual = header[i] == null ? "" : header[i].replace("\uFEFF", "").strip();
            if (!expectedHeaders.get(i).equalsIgnoreCase(actual)) {
                throw new IllegalArgumentException(expectedMessage);
            }
        }
    }

    static boolean isBlankRow(String[] cells) {
        if (cells == null || cells.length == 0) {
            return true;
        }
        for (String c : cells) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }

    static String cellAt(String[] cells, int index) {
        if (cells == null || index >= cells.length || cells[index] == null) {
            return "";
        }
        return cells[index].strip();
    }

    static Integer parseCount(String raw) {
        if (raw == null) {
            return null;
        }
        String clean = raw.strip();
        if (!clean.matches("^\\d+$")) {
            return null;
        }
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String[]> readCsvLines(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    line = line.replaceFirst("^\uFEFF", "");
                    first = false;
                }
                rows.add(splitCsvLine(line));
            }
        }
        return rows;
    }

    private static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().strip());
        return cells.toArray(new String[0]);
    }

    private static List<String[]> readExcelLines(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                return rows;
            }
            int last = sheet.getLastRowNum();
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rows.add(new String[0]);
                    continue;
                }
                int lastCell = Math.max(row.getLastCellNum(), (short) 5);
                String[] cells = new String[lastCell];
                for (int c = 0; c < lastCell; c++) {
                    cells[c] = getCellString(row.getCell(c));
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue().strip();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double val = cell.getNumericCellValue();
            return val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }
}
