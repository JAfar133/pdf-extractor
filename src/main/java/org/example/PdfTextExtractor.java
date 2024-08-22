package org.example;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.*;
import technology.tabula.*;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.apache.lucene.search.spell.LevenshteinDistance;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

public class PdfTextExtractor {

    private static final String FOOTER_REGION = "footer";
    private static final String HEADER_REGION = "header";
    private static final String BODY_REGION = "body";
    private static final List<String> POTENTIAL_EXTRA_BODY_LINES = List.of(
            "(No file attached)", "Please upload supporting document"
    );
    private static final float SIMILARITY_THRESHOLD = 0.8f;
    private static final float FREQUENCY_THRESHOLD = 0.7f;
    private static final int HEADER_AND_FOOTER_HEIGHT = 75;

    // Levenshtein distance calculator for string similarity
    private static final LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
    private static final boolean convertTableToReadableFormat = true;
    private static final boolean withHeader = true;

    public List<FilePage> extract(PDDocument document, boolean cleanPages) throws IOException {
        ObjectExtractor extractor = new ObjectExtractor(document);
        PageIterator pageIterator = extractor.extract();
        List<FilePage> filePages = new ArrayList<>();
        if (cleanPages) {

            // Determine the dimensions of the PDF page
            PDRectangle mediaBox = document.getPage(0).getMediaBox();
            float lowerLeftY = mediaBox.getLowerLeftY();
            float upperRightY = mediaBox.getUpperRightY();
            float lowerLeftX = mediaBox.getLowerLeftX();
            float upperRightX = mediaBox.getUpperRightX();

            RectangleRegion footerRegion = new RectangleRegion(lowerLeftX, lowerLeftY, upperRightX - lowerLeftX, HEADER_AND_FOOTER_HEIGHT, FOOTER_REGION);
            RectangleRegion headerRegion = new RectangleRegion(lowerLeftX, upperRightY - HEADER_AND_FOOTER_HEIGHT, upperRightX - lowerLeftX, HEADER_AND_FOOTER_HEIGHT, HEADER_REGION);
            RectangleRegion bodyRegion = new RectangleRegion(lowerLeftX, lowerLeftY + HEADER_AND_FOOTER_HEIGHT, upperRightX - lowerLeftX, upperRightY - lowerLeftY - HEADER_AND_FOOTER_HEIGHT * 2, BODY_REGION);

            Map<String, Set<RectangleRegion>> duplicates = findRepetitiveLinesAndPatterns(document, footerRegion, headerRegion, bodyRegion);

            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                if (page.getPageNumber() != -1) {
                    filePages.add(new FilePage(getPdfPageText(document, page, duplicates), page.getPageNumber()));
                }
            }
        } else {
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                filePages.add(new FilePage(getPdfPageText(document, page), page.getPageNumber()));
            }
        }

        return filePages;
    }

    @SneakyThrows
    private String getPdfPageText(PDDocument document, Page page) {
        PDFTextStripper pdfStripper = new PDFTextStripper();
        pdfStripper.setStartPage(page.getPageNumber());
        pdfStripper.setEndPage(page.getPageNumber());
        return pdfStripper.getText(document);
    }

    @SneakyThrows
    private String getPdfPageText(PDDocument document, Page page, Map<String, Set<RectangleRegion>> duplicates) {
        return removeRepetitiveGroups(document, page, duplicates);
    }

    private String removeRepetitiveGroups(PDDocument document, Page page, Map<String, Set<RectangleRegion>> duplicates) throws
            IOException {
        List<PdfTable> pageTables = convertTableToReadableFormat ? extractTablesFromPdfPage(page) : new ArrayList<>();
        boolean[] tableVisit = new boolean[pageTables.size()];
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String string, List<TextPosition> textPositions) throws IOException {

                // The contents of the table are replaced by the readable format
                for (int i = 0; i < pageTables.size(); i++) {
                    PdfTable table = pageTables.get(i);
                    TextPosition firstTextPosition = textPositions.get(0);
                    if (isInsideTable(firstTextPosition, table)) {
                        if (!tableVisit[i]) {
                            super.writeString("\n" + table.getText(), textPositions);
                            tableVisit[i] = true;
                        }
                        return;
                    }
                }

                Set<RectangleRegion> regions = duplicates.get(string.trim());
                boolean isWrite = true;

                // If the text is contained in the area to be deleted, skip it
                if (regions != null) {
                    isWrite = textPositions.stream().noneMatch(text ->
                            regions.stream().anyMatch(region -> region.contains(text.getX(), text.getY()))
                    );
                }
                if (isWrite) {
                    super.writeString(string, textPositions);
                }
            }

            private boolean isInsideTable(TextPosition text, PdfTable table) {
                float x = text.getX();
                float y = text.getY();
                return x >= table.getStartX() && x <= table.getEndX() && y >= table.getStartY() && y <= table.getEndY();
            }
        };

        stripper.setStartPage(page.getPageNumber());
        stripper.setEndPage(page.getPageNumber());
        String extractedText = stripper.getText(document);

        return removeExtraEmptyLines(extractedText).trim() + "\n";
    }

    private String removeExtraEmptyLines(String text) {
        return text.replaceAll("(\r\n){2,}", "\r\n");
    }

    private Map<String, Set<RectangleRegion>> findRepetitiveLinesAndPatterns(PDDocument document, RectangleRegion footerRegion, RectangleRegion headerRegion, RectangleRegion bodyRegion) throws IOException {
        Map<String, LineInfo> commonLines = new HashMap<>();
        PDFTextStripperByArea stripperByArea = new PDFTextStripperByArea();
        stripperByArea.addRegion(footerRegion.getRegionStr(), footerRegion);
        stripperByArea.addRegion(headerRegion.getRegionStr(), headerRegion);
        stripperByArea.addRegion(bodyRegion.getRegionStr(), bodyRegion);
        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripperByArea.extractRegions(document.getPage(i - 1));
            String footerText = stripperByArea.getTextForRegion(footerRegion.getRegionStr());
            String headerText = stripperByArea.getTextForRegion(headerRegion.getRegionStr());
            String bodyText = stripperByArea.getTextForRegion(bodyRegion.getRegionStr());

            // Collect all lines of text in a list considering their regions
            List<Pair<String, RectangleRegion>> allLines = new ArrayList<>();
            for (String line : footerText.split("\r?\n")) {
                allLines.add(new Pair<>(line.trim(), footerRegion));
            }
            for (String line : headerText.split("\r?\n")) {
                allLines.add(new Pair<>(line.trim(), headerRegion));
            }

            // Check if the body text contains any of the potential extra lines
            for (String line :  bodyText.split("\r?\n")) {
                if (POTENTIAL_EXTRA_BODY_LINES.stream().anyMatch(ll -> levenshteinDistance.getDistance(ll, line.trim()) >= SIMILARITY_THRESHOLD)) {
                    allLines.add(new Pair<>(line.trim(), bodyRegion));
                }
            }

            // Grouping identical rows by counts and regions
            for (Pair<String, RectangleRegion> line : allLines) {
                String cleanedLine = line.getFirst().trim();
                if (!cleanedLine.isEmpty()) {
                    LineInfo lineInfo = commonLines.get(cleanedLine);
                    if (lineInfo == null) {
                        Set<RectangleRegion> regions = new HashSet<>();
                        regions.add(line.getSecond());
                        commonLines.put(cleanedLine, new LineInfo(regions, 1));
                    } else {
                        lineInfo.addRegion(line.getSecond());
                        lineInfo.incrementCount();
                    }
                }
            }
        }

        return findDuplicateSets(commonLines, document.getNumberOfPages());
    }

    private boolean isLineOnBodyRegion(LineInfo line) {
        return line.regions.stream().allMatch(r -> r.getRegionStr().equals(BODY_REGION));
    }

    private Map<String, Set<RectangleRegion>> findDuplicateSets(Map<String, LineInfo> lineCounts, int totalPages) {

        Map<String, Set<RectangleRegion>> linesToRemove = new HashMap<>();
        List<String> lines = new ArrayList<>(lineCounts.keySet());

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineInfo lineInfo = lineCounts.get(line);
            if ((float) lineInfo.getCount() / totalPages >= FREQUENCY_THRESHOLD // Frequently occurring lines
                    || (StringUtils.isNumeric(line) && !isLineOnBodyRegion(lineInfo)) // Page numbers in footer and header regions
                    || (isLineOnBodyRegion(lineInfo) && POTENTIAL_EXTRA_BODY_LINES.stream().anyMatch(ll ->
                    levenshteinDistance.getDistance(ll, line) >= SIMILARITY_THRESHOLD)) // The most similar lines to POTENTIAL_EXTRA_BODY_LINES
            ) {
                linesToRemove.put(line, lineInfo.getRegions());
                continue;
            }

            // Identify similar patterns across multiple lines
            Map<String, Set<RectangleRegion>> patterns = new HashMap<>();
            patterns.put(line, lineInfo.getRegions());
            int patternCount = lineCounts.get(line).getCount();
            for (int j = i + 1; j < lines.size(); j++) {
                String otherPattern = lines.get(j);
                LineInfo lineInfo1 = lineCounts.get(otherPattern);
                if (levenshteinDistance.getDistance(line, otherPattern) >= SIMILARITY_THRESHOLD) {
                    patterns.put(otherPattern, lineInfo1.getRegions());
                    patternCount += lineInfo1.getCount();
                }
            }
            if ((float) patternCount / totalPages > FREQUENCY_THRESHOLD) {
                linesToRemove.putAll(patterns);
            }

        }

        return linesToRemove;
    }

    private List<PdfTable> extractTablesFromPdfPage(Page page) {
        SpreadsheetExtractionAlgorithm algorithm = new SpreadsheetExtractionAlgorithm();
        List<Table> tables = algorithm.extract(page);
        List<PdfTable> pdfTables = new ArrayList<>();

        for (Table table : tables) {
            // Skip invalid tables
            if (!isValidTable(table)) {
                continue;
            }

            // Clean up the table (remove empty columns)
            List<List<RectangularTextContainer>> rows = cleanTable(table);
            if (rows.isEmpty()) continue;

            PdfTable pdfTable = new PdfTable();

            RectangularTextContainer firstCell = rows.get(0).get(0);
            RectangularTextContainer lastCell = findLastCell(rows);
            if (lastCell == null) continue;

            // Set the bounding box coordinates for the PdfTable
            pdfTable.setStartX((float) firstCell.getX());
            pdfTable.setStartY((float) firstCell.getY());
            pdfTable.setEndX((float) (lastCell.getX() + lastCell.getWidth()));
            pdfTable.setEndY((float) (lastCell.getY() + lastCell.getHeight()));

            StringBuilder tableStr = new StringBuilder();
            boolean isFirstRow = true;

            for (List<RectangularTextContainer> row : rows) {
                if (row.isEmpty()) continue;

                appendRowToTableString(tableStr, row);
                if (isFirstRow) {
                    if (withHeader) {
                        appendHeaderSeparator(tableStr, row.size());
                    }
                    isFirstRow = false;
                }
            }

            pdfTable.setText(tableStr.toString());
            pdfTables.add(pdfTable);
        }

        return pdfTables;
    }

    private void appendRowToTableString(StringBuilder tableStr, List<RectangularTextContainer> row) {
        for (RectangularTextContainer cell : row) {
            String cellText = cell.getText().replaceAll("\\r", " ");
            tableStr.append("| ").append(cellText).append(" ");
        }
        tableStr.append("|\n");
    }

    private void appendHeaderSeparator(StringBuilder tableStr, int columnCount) {
        tableStr.append("|---".repeat(columnCount)).append("|\n");
    }


    private float getRowWidth(List<RectangularTextContainer> row) {
        float rowWidth = 0;
        for (RectangularTextContainer cell: row) {
            rowWidth += cell.width;
        }

        return rowWidth;
    }

    private boolean isValidTable(Table table) {
        if (table.getRows().size() <= 1) {
            return false;
        }

        float firstRowWidth = getRowWidth(table.getRows().get(0));

        return table.getRows().stream().noneMatch(row -> firstRowWidth != getRowWidth(row))
                && table.getRows().stream().filter(row -> !cleanRow(row).isEmpty()).count() > 1
                && table.getRows().stream().anyMatch(row -> cleanRow(row).size() > 1);
    }

    private RectangularTextContainer findLastCell(List<List<RectangularTextContainer>> rows) {

        for (int i = rows.size() - 1; i >= 0; i--) {
            List<RectangularTextContainer> row = rows.get(i);
            for (int j = row.size() - 1; j >= 0; j--) {
                RectangularTextContainer cell = row.get(j);
                if (cell.x != 0 || cell.y != 0 || cell.width != 0 || cell.height != 0) {
                    return cell;
                }
            }
        }

        return null;
    }

    private List<RectangularTextContainer> cleanRow(List<RectangularTextContainer> cells) {
        List<RectangularTextContainer> cleanRow = new ArrayList<>();

        for (RectangularTextContainer cell : cells) {
            String text = cell.getText().trim();

            if (!text.isEmpty()) {
                if (cell instanceof Cell) {
                    cleanRow.add(cell);
                }
            }
        }

        return cleanRow;
    }

    private List<List<RectangularTextContainer>> cleanTable(Table table) {
        List<List<RectangularTextContainer>> rows = new ArrayList<>();
        boolean[] columnsToDelete = new boolean[getMaxRowsSize(table.getRows())];
        for (int i = table.getRows().size() - 1; i >= 0; i--) {
            if (table.getRows().get(i).isEmpty()) {
                columnsToDelete[i] = true;
            }
        }
        for (int j = 0; j < table.getRows().size(); j++) {
            List<RectangularTextContainer> currentRow = table.getRows().get(j);
            List<RectangularTextContainer> row = new ArrayList<>();
            for (int i = 0; i < columnsToDelete.length; i++) {
                if (!columnsToDelete[i]) {
                    row.add(currentRow.get(i));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private int getMaxRowsSize(List<List<RectangularTextContainer>> rows) {
        int max = 0;
        for (List<RectangularTextContainer> row: rows) {
            if (row.size() > max) {
                max = row.size();
            }
        }
        return max;
    }

    private CellInfo getCellByX(float x, List<RectangularTextContainer> row) {
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i).x == x) {
                return new CellInfo(row.get(i), i);
            }
        }
        return null;
    }

    @Data
    @Getter
    @AllArgsConstructor
    static class CellInfo {
        private RectangularTextContainer cell;
        private int index;
    }

    @Getter
    static class RectangleRegion extends Rectangle2D.Float {

        public RectangleRegion(float x, float y, float w, float h, String regionStr) {
            super(x, y, w, h);
            this.regionStr = regionStr;
        }

        private final String regionStr;

    }

    @Getter
    @Setter
    @AllArgsConstructor
    static class LineInfo {
        private Set<RectangleRegion> regions;
        private int count;

        public void addRegion(RectangleRegion region) {
            this.regions.add(region);
        }
        public void incrementCount() {
            this.count++;
        }
    }

    @Getter
    @Setter
    static class PdfTable {
        private float startX;
        private float startY;
        private float endX;
        private float endY;
        private String text;
    }
}
