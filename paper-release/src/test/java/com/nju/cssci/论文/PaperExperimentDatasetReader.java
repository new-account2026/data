package com.nju.cssci.论文;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Header-driven, read-only dataset access. */
public final class PaperExperimentDatasetReader {
    private static final List<String> QUERY_ID = List.of("query_id", "queryid", "id", "case_id");
    private static final List<String> QUERY = List.of("query", "question", "user_query", "prompt");
    private static final List<String> ACTION = List.of("gold_action", "action_label", "action", "label_action");
    private static final List<String> ROUTE = List.of("gold_route", "route_label", "route", "source_label");

    public Dataset read(Path path) throws Exception {
        Path source = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Dataset does not exist: " + source);
        List<PaperExperimentCase> cases = new ArrayList<>();
        int totalRows = 0;
        String sheetName;
        List<String> headers = new ArrayList<>();
        try (InputStream input = Files.newInputStream(source); Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            sheetName = sheet.getSheetName();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw new IllegalArgumentException("Dataset has no header row");
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : header) {
                String raw = formatter.formatCellValue(cell).trim();
                headers.add(raw);
                columns.put(normalize(raw), cell.getColumnIndex());
            }
            int idCol = find(columns, QUERY_ID, false);
            int queryCol = find(columns, QUERY, true);
            int actionCol = find(columns, ACTION, false);
            int routeCol = find(columns, ROUTE, false);
            for (int rowIndex = header.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                totalRows++;
                String query = cell(row, queryCol, formatter).trim();
                if (query.isEmpty()) continue;
                String id = cell(row, idCol, formatter).trim();
                if (id.isEmpty()) id = String.format(Locale.ROOT, "ROW_%06d", rowIndex + 1);
                Map<String, String> allGold = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : columns.entrySet()) {
                    String key = entry.getKey();
                    if (key.contains("gold") || key.contains("label")) {
                        allGold.put(key, cell(row, entry.getValue(), formatter).trim());
                    }
                }
                cases.add(new PaperExperimentCase(id, query,
                        upper(cell(row, actionCol, formatter)), upper(cell(row, routeCol, formatter)),
                        rowIndex + 1, allGold));
            }
        }
        return new Dataset(source, sheetName, headers, totalRows, cases, sha256(source));
    }

    public List<PaperExperimentCase> select(Dataset dataset, int sampleSize, long seed) {
        List<PaperExperimentCase> selected = new ArrayList<>(dataset.validCases());
        if (sampleSize < 0) return selected;
        Collections.shuffle(selected, new Random(seed));
        return new ArrayList<>(selected.subList(0, Math.min(sampleSize, selected.size())));
    }

    /** Validation-only stratification; this sample must never feed population metrics. */
    public List<PaperExperimentCase> branchCoverage(Dataset dataset, int perBranch, long seed) {
        Map<String, List<PaperExperimentCase>> buckets = new LinkedHashMap<>();
        for (String key : List.of("ACTION:ASK", "ACTION:ANSWER", "ACTION:RETRIEVE", "ACTION:REFUSE",
                "ROUTE:DB", "ROUTE:WEB", "ROUTE:DB+WEB")) buckets.put(key, new ArrayList<>());
        for (PaperExperimentCase value : dataset.validCases()) {
            List<PaperExperimentCase> action = buckets.get("ACTION:" + value.goldAction());
            if (action != null) action.add(value);
            if ("RETRIEVE".equals(value.goldAction())) {
                List<PaperExperimentCase> route = buckets.get("ROUTE:" + value.goldRoute());
                if (route != null) route.add(value);
            }
        }
        LinkedHashMap<String, PaperExperimentCase> result = new LinkedHashMap<>();
        int bucketIndex = 0;
        for (List<PaperExperimentCase> bucket : buckets.values()) {
            Collections.shuffle(bucket, new Random(seed + bucketIndex++));
            for (int i = 0; i < Math.min(perBranch, bucket.size()); i++) {
                result.putIfAbsent(bucket.get(i).queryId(), bucket.get(i));
            }
        }
        return new ArrayList<>(result.values());
    }

    private static int find(Map<String, Integer> columns, List<String> aliases, boolean required) {
        for (String alias : aliases) {
            Integer value = columns.get(normalize(alias));
            if (value != null) return value;
        }
        if (required) throw new IllegalArgumentException("Required column missing; accepted aliases=" + aliases);
        return -1;
    }

    private static String cell(Row row, int index, DataFormatter formatter) {
        if (row == null || index < 0) return "";
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String upper(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record Dataset(Path path, String sheetName, List<String> headers, int totalRows,
                          List<PaperExperimentCase> validCases, String sha256) { }
}
