package org.example;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.*;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;
import org.apache.pdfbox.util.Matrix;
import technology.tabula.*;
import technology.tabula.extractors.ExtractionAlgorithm;
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
    private static final int MIN_TABLE_CELL_HEIGHT_AND_WIDTH = 10;

    // Levenshtein distance calculator for string similarity
    private static final LevenshteinDistance levenshteinDistance = new LevenshteinDistance();
    private static final boolean convertTableToReadableFormat = true;
    private static final boolean tablesWithHeader = false;
    private final PDFTextStripperByArea stripperByArea;
    private final PDDocument document;
    private final PDRectangle mediaBox;
    private final ExtractionAlgorithm tableExtractionAlgorithm;
    private final Map<String, FormField> formData = new HashMap<>();
    private final String FORM_DATA_PREFIX = ".)-&f*?5%f"; // Prefix for generating unique identifiers
    private final String QUESTION_START_PREFIX = "#%*&q1v1\r";
    private final boolean isSselDocument;

    public PdfTextExtractor(PDDocument document) throws IOException {
        this.document = document;
        this.stripperByArea = new PDFTextStripperByArea();
        this.tableExtractionAlgorithm = new SpreadsheetExtractionAlgorithm().withMinSpacingBetweenRulings(MIN_TABLE_CELL_HEIGHT_AND_WIDTH);
        this.mediaBox = document.getPage(0).getMediaBox();
        this.isSselDocument = hasTableOfContentPage() && hasProjectDetailsPage();
    }

    public boolean isSselDocument() {
        return isSselDocument;
    }

    private boolean hasTableOfContentPage() {
        String pageText = getPdfPageText(3);
        return pageText.startsWith("TABLE OF CONTENTS");
    }

    public String getQuestionStartPrefix() {
        return QUESTION_START_PREFIX;
    }

    private final List<String> PROJECT_DETAILS_COLUMNS = List.of("Project Title", "Status", "Author", "Response Deadline", "Created", "Published", "Visibility", "Categories", "Scoring Formula", "Synopsis");
    private boolean hasProjectDetailsPage() {
        ObjectExtractor extractor = new ObjectExtractor(document);
        Page page = extractor.extract(2);
        List<Table> tables = (List<Table>) tableExtractionAlgorithm.extract(page);
        if (tables.size() != 1) return false;
        Table table = tables.get(0);
        List<Boolean> columns = new ArrayList<>(Collections.nCopies(PROJECT_DETAILS_COLUMNS.size(), false));
        for (int i = 0; i < table.getRows().size(); i++) {
            String cellText = table.getRows().get(i).get(0).getText();
            int columnIndex = PROJECT_DETAILS_COLUMNS.indexOf(cellText);

            if (columnIndex != -1) {
                columns.set(columnIndex, true);
            }
        }

        return columns.stream().allMatch(col -> col);
    }

    public List<FilePage> extract(boolean cleanPages) throws IOException {
        ObjectExtractor extractor = new ObjectExtractor(document);
        PageIterator pageIterator = extractor.extract();
        List<FilePage> filePages = new ArrayList<>();
        if (cleanPages) {
            // Determine the dimensions of the PDF page
            List<RectangleRegion> regions = getRectangleRegions(mediaBox);

            processFormData();

            Map<String, Set<RectangleRegion>> duplicates = findRepetitiveLinesAndPatterns(regions);

            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                filePages.add(new FilePage(getPdfPageText(page, duplicates), page.getPageNumber()));
            }
        } else {
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                filePages.add(new FilePage(getPdfPageText(page), page.getPageNumber()));
            }
        }

        return filePages;
    }

    private void processFormData() throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null) return;
        int counter = 1;

        for (PDField field : acroForm.getFields()) {
            String uniqueId = String.format("%s%d", FORM_DATA_PREFIX, counter); // Generate the unique ID before processing
            PDRectangle rectangle = field.getWidgets().get(0).getRectangle();
            float yFromTop = mediaBox.getHeight() - rectangle.getUpperRightY();

            float fontSize = 10;

            if (field instanceof PDCheckBox) {
                formData.put(uniqueId, new FormField(
                        rectangle.getLowerLeftX(),
                        yFromTop,
                        rectangle.getWidth(),
                        rectangle.getHeight(),
                        ((PDCheckBox) field).isChecked() ? "[x]" : "[ ]",
                        getFieldPageIndex(field)));
                writeFieldInPDF(fontSize, rectangle.getLowerLeftX(), yFromTop, uniqueId, formData.get(uniqueId).pageIndex);

            } else if (field instanceof PDRadioButton) {
                PDRadioButton radioButton = (PDRadioButton) field;
                String selectedValue = radioButton.getValueAsString();
                List<PDAnnotationWidget> widgets = radioButton.getWidgets();

                for (PDAnnotationWidget widget : widgets) {
                    String buttonLabel = widget.getAppearanceState().getName();
                    String displayValue = buttonLabel.equals(selectedValue) ? "(x)" : "( )";
                    PDRectangle widgetRect = widget.getRectangle();
                    float yFromTopw = mediaBox.getHeight() - widgetRect.getUpperRightY();
                    formData.put(uniqueId, new FormField(widgetRect.getLowerLeftX(), yFromTopw, widgetRect.getWidth(), widgetRect.getHeight(), displayValue, getFieldPageIndex(field)));

                    writeFieldInPDF(fontSize, widgetRect.getLowerLeftX(), yFromTopw, uniqueId, formData.get(uniqueId).pageIndex);
                    counter++;
                    uniqueId = String.format("%s%d", FORM_DATA_PREFIX, counter);
                }

            } else if (field instanceof PDComboBox) {
                PDComboBox comboBox = (PDComboBox) field;
                List<String> options = comboBox.getOptionsExportValues();
                List<String> values = comboBox.getOptionsDisplayValues();

                int index = options.indexOf(comboBox.getValue().get(0));

                String selectedValue = values.get(index);

                formData.put(uniqueId, new FormField(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), selectedValue, getFieldPageIndex(field)));

                writeFieldInPDF(fontSize, rectangle.getLowerLeftX(), yFromTop, uniqueId, formData.get(uniqueId).pageIndex);

            } else {
                if (field instanceof PDTextField) {
                    fontSize = getFontSizeFromDA(((PDTextField) field).getDefaultAppearance());
                    if (fontSize == 0) {
                        fontSize = 10;
                    }
                }

                formData.put(uniqueId, new FormField(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), field.getValueAsString(), getFieldPageIndex(field)));
                writeFieldInPDF(fontSize, rectangle.getLowerLeftX(), yFromTop, uniqueId, formData.get(uniqueId).pageIndex);
            }

            counter++;
        }
    }

    private void writeFieldInPDF(float fontSize, float x, float y, String text, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            contentStream.beginText();
            contentStream.setFont(PDType1Font.TIMES_ROMAN, 1); // tiny text
            contentStream.setTextMatrix(Matrix.getTranslateInstance(x, page.getMediaBox().getHeight() - y - fontSize / 2));

            contentStream.showText(text);
            contentStream.endText();
        }
    }
    private float getFontSizeFromDA(String da) {
        // String Example DA: "/F3 10 Tf 0 g"
        if (da == null) return 0.0f;
        try {
            String[] tokens = da.split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                if ("Tf".equals(tokens[i]) && i > 0) {
                    return Float.parseFloat(tokens[i - 1]);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    private int getFieldPageIndex(PDField field) throws IOException {
        for (PDAnnotationWidget widget : field.getWidgets()) {
            PDPage page = widget.getPage();
            if (page == null) {
                // incorrect PDF. Plan B: try all pages to check the annotations.
                for (int p = 0; p < document.getNumberOfPages(); ++p) {
                    List<PDAnnotation> annotations = document.getPage(p).getAnnotations();
                    for (PDAnnotation ann : annotations) {
                        if (ann.getCOSObject() == widget.getCOSObject()) {
                            return p;
                        }
                    }
                }
                continue;
            }
            return document.getPages().indexOf(page);
        }
        return -1;
    }

    private List<RectangleRegion> getRectangleRegions(PDRectangle mediaBox) {
        float lowerLeftY = mediaBox.getLowerLeftY();
        float upperRightY = mediaBox.getUpperRightY();
        float lowerLeftX = mediaBox.getLowerLeftX();
        float upperRightX = mediaBox.getUpperRightX();

        RectangleRegion headerRegion = new RectangleRegion(lowerLeftX, lowerLeftY, upperRightX - lowerLeftX, HEADER_AND_FOOTER_HEIGHT, HEADER_REGION);
        RectangleRegion footerRegion = new RectangleRegion(lowerLeftX, upperRightY - HEADER_AND_FOOTER_HEIGHT, upperRightX - lowerLeftX, HEADER_AND_FOOTER_HEIGHT, FOOTER_REGION);
        RectangleRegion bodyRegion = new RectangleRegion(lowerLeftX, lowerLeftY + HEADER_AND_FOOTER_HEIGHT, upperRightX - lowerLeftX, upperRightY - lowerLeftY - HEADER_AND_FOOTER_HEIGHT * 2, BODY_REGION);

        return List.of(footerRegion, headerRegion, bodyRegion);
    }

    @SneakyThrows
    private String getPdfPageText(Page page) {
        PDFTextStripper pdfStripper = new PDFTextStripper();
        pdfStripper.setStartPage(page.getPageNumber());
        pdfStripper.setEndPage(page.getPageNumber());
        return pdfStripper.getText(document);
    }

    @SneakyThrows
    private String getPdfPageText(int pageNumber) {
        PDFTextStripper pdfStripper = new PDFTextStripper();
        pdfStripper.setStartPage(pageNumber);
        pdfStripper.setEndPage(pageNumber);
        return pdfStripper.getText(document);
    }

    @SneakyThrows
    private String getPdfPageText(Page page, Map<String, Set<RectangleRegion>> duplicates) {
        return getPageText(document, page, duplicates);
    }

    public String getPageText(PDDocument document, Page page, Map<String, Set<RectangleRegion>> duplicates) throws IOException {
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

                // If the string is contained in the area to be deleted, skip it
                if (regions != null) {
                    isWrite = textPositions.stream().noneMatch(text ->
                            regions.stream().anyMatch(region -> region.contains(text.getX(), text.getY()))
                    );
                }

                if (isWrite) {
                    if (formData.get(string) != null) {
                        super.writeString(formData.get(string).getText().trim());
                    } else {
                        super.writeString(string, textPositions);
                    }
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
        stripper.setSortByPosition(true);
        String extractedText = stripper.getText(document);

        return removeExtraEmptyLines(extractedText).trim() + "\n";
    }

    @Data
    @AllArgsConstructor
    private static class FormField {
        private float x;
        private float y;
        private float width;
        private float height;
        private String text;
        private int pageIndex;
    }

    private Map<String, Set<RectangleRegion>> findRepetitiveLinesAndPatterns(List<RectangleRegion> regions) throws IOException {
        Map<String, LineInfo> commonLines = new HashMap<>();

        // Add all regions to the PDFTextStripperByArea
        for (RectangleRegion region: regions) {
            stripperByArea.addRegion(region.getRegionStr(), region);
        }
        // Extract lines from each region
        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripperByArea.extractRegions(document.getPage(i - 1));
            List<Pair<String, RectangleRegion>> allLines = new ArrayList<>();
            for (RectangleRegion region: regions) {
                String text = stripperByArea.getTextForRegion(region.getRegionStr());
                for (String line: text.split("\r?\n")) {
                    if (region.getRegionStr().equals(BODY_REGION)) {
                        if (isExtraBodyLinesContainsSimilarLine(line)) {
                            allLines.add(new Pair<>(line.trim(), region));
                        }
                    } else {
                        allLines.add(new Pair<>(line.trim(), region));
                    }
                }
            }

            // Grouping identical rows by counts and regions
            for (Pair<String, RectangleRegion> line : allLines) {
                String cleanedLine = line.getFirst();
                commonLines.computeIfAbsent(cleanedLine, k -> new LineInfo(new HashSet<>(), 0))
                        .addRegion(line.getSecond());
                commonLines.get(cleanedLine).incrementCount();
            }
        }

        return findDuplicateSets(commonLines, document.getNumberOfPages());
    }

    private Map<String, Set<RectangleRegion>> findDuplicateSets(Map<String, LineInfo> lineCounts, int totalPages) {

        Map<String, Set<RectangleRegion>> linesToRemove = new HashMap<>();
        List<String> lines = new ArrayList<>(lineCounts.keySet());

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LineInfo lineInfo = lineCounts.get(line);
            if ((float) lineInfo.getCount() / totalPages >= FREQUENCY_THRESHOLD // Frequently occurring lines
                    || (StringUtils.isNumeric(line) && !isLineOnBodyRegion(lineInfo)) // Page numbers in footer and header regions
                    || (isLineOnBodyRegion(lineInfo) && isExtraBodyLinesContainsSimilarLine(line)) // The most similar lines to POTENTIAL_EXTRA_BODY_LINES
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

    private List<PdfTable> extractTablesFromPdfPage(Page page) throws IOException {
        List<Table> tables = (List<Table>) tableExtractionAlgorithm.extract(page);
        List<PdfTable> pdfTables = new ArrayList<>();

        for (Table table : tables) {
            // Skip invalid tables
            if (isSselDocument && isQuestionTable(table)) {
                pdfTables.add(selectQuestion(table));
                continue;
            }
            if (!isValidTable(table)) {
                continue;
            }

            // Clean up the table (remove empty columns)
            List<List<RectangularTextContainer>> rows = cleanTable(table);
            if (rows.isEmpty()) continue;

            PdfTable pdfTable = new PdfTable(
                    (float) table.getX(),
                    (float) table.getY(),
                    (float) (table.getX() + table.getWidth()),
                    (float) (table.getY() + table.getHeight())
            );

            List<String> tableFormDatas = new ArrayList<>();

            StringBuilder tableStr = new StringBuilder();
            boolean isFirstRow = true;

            for (List<RectangularTextContainer> row : rows) {
                if (row.isEmpty() || isRowTextEmpty(row)) continue;
                appendRowToTableString(tableStr, row, page, tableFormDatas, !isSselDocument || row.size() != 1);
                if (isFirstRow) {
                    if (tablesWithHeader) {
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

    private PdfTable selectQuestion(Table table) {
        PdfTable pdfTable = new PdfTable(
                (float) table.getX(),
                (float) table.getY(),
                (float) (table.getX() + table.getWidth()),
                (float) (table.getY() + table.getHeight())
        );

        pdfTable.setText(QUESTION_START_PREFIX + table.getRows().get(0).get(0).getText());

        return pdfTable;
    }

    private boolean isQuestionTable(Table table) {
        return table != null && table.getRows().size() == 1 && table.getRows().get(0).size() == 1 && isQuestionFormat(table.getRows().get(0).get(0).getText());
    }

    private boolean isQuestionFormat(String text) {
        // Example: 1.4.3 Question1?
        String regex = "^\\d+(\\.\\d+)*\\s\\w+";
        Pattern pattern = Pattern.compile(regex);
        return pattern.matcher(text).find();
    }

    private boolean isCheckBoxAnswer(String text) {
        String regex = "^(\\[\\s+\\]|\\[x\\])\\s.*";
        return text.matches(regex);
    }

    private boolean isRadioButtonAnswer(String text) {
        String regex = "^(\\(\\s+\\)|\\(o\\))\\s.*";
        return text.matches(regex);
    }

    private boolean isCheckBoxSelected(String text) {
        String regex = "^\\[x\\]\\s.*";
        return text.matches(regex);
    }

    private boolean isRadioButtonSelected(String text) {
        String regex = "^\\(o\\)\\s.*";
        return text.matches(regex);
    }

    private void appendRowToTableString(
            StringBuilder tableStr,
            List<RectangularTextContainer> row,
            Page page,
            List<String> tableFormDatas,
            boolean useSeparator) throws IOException {
        for (RectangularTextContainer cell : row) {
            List<String> formKeys = getFormKeysInsideCell(cell, page.getPageNumber());
            String cellText = getTextByTextArea(cell, page).trim().replaceAll("\\r?\\n", " ");
            if (!formKeys.isEmpty()) {
                for (String key: formKeys) {
                    tableFormDatas.add(key);
                    cellText = cellText.replace(key, formData.get(key).getText().trim().replaceAll("\\r?\\n", " "));
                }
            } else {
                for (String key: tableFormDatas) {
                    if (cellText.contains(key)) {
                        cellText = cellText.replace(key, "");
                    }
                }
                if(isCheckBoxAnswer(cellText)) {
                    if (!isCheckBoxSelected(cellText)) {
                        continue;
                    }
                } else if (isRadioButtonAnswer(cellText)) {
                    String answer = extractRadioButtonAnswer(cellText);
                    if (answer == null) {
                        cellText = "";
                    } else {
                        cellText = answer;
                    }
                }
            }
            if (useSeparator) {
                tableStr.append("| ").append(cellText).append(" ");
            } else {
                tableStr.append(cellText).append(" ");
            }

        }
        if (useSeparator) {
            tableStr.append("|\n");
        } else {
            tableStr.append("\n");
        }

    }

    private String extractRadioButtonAnswer(String cellText) {
        Pattern pattern = Pattern.compile("\\(o\\)\\s[^()]+");
        Matcher matcher = pattern.matcher(cellText);

        if(matcher.find()) {
            return matcher.group().trim();
        }

        return null;
    }

    private List<String> getFormKeysInsideCell(RectangularTextContainer cell, int pageNumber) {

        List<String> keys = new ArrayList<>();
        for (String key: formData.keySet()) {
            FormField field = formData.get(key);
            if (field.getPageIndex() == pageNumber - 1 && cell.getX() <= field.getX()
                    && cell.getY() <= field.getY()
                    && (cell.getX() + cell.getWidth()) >= (field.getX() + field.getWidth())
                    && (cell.getY() + cell.getHeight()) >= (field.getY() + field.getHeight())) {
                keys.add(key);
            }
        }
        return keys;
    }

    private boolean isRowTextEmpty(List<RectangularTextContainer> row) {
        return row.stream().allMatch(cell -> cell.getText().isEmpty());
    }

    private String getTextByTextArea(RectangularTextContainer cell, Page page) throws IOException {
        final String regionName = "cellRegion";
        RectangleRegion cellRegion = new RectangleRegion(cell.x, cell.y, cell.width, cell.height, regionName);
        stripperByArea.addRegion(regionName, cellRegion);

        stripperByArea.extractRegions(document.getPage(page.getPageNumber() - 1));
        return stripperByArea.getTextForRegion(regionName);
    }

    // Remove empty cells
    private List<RectangularTextContainer> cleanRow(List<RectangularTextContainer> cells) {
        return cells.stream().filter(cell -> !cell.getText().trim().isEmpty() && cell instanceof Cell)
                .collect(Collectors.toList());
    }

    // Remove empty columns
    private List<List<RectangularTextContainer>> cleanTable(Table table) {
        List<List<RectangularTextContainer>> rows = new ArrayList<>();
        boolean[] columnsToDelete = new boolean[getMaxRowSize(table.getRows())];
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

    private boolean isLineOnBodyRegion(LineInfo line) {
        return line.getRegions().stream().allMatch(r -> r.getRegionStr().equals(BODY_REGION));
    }

    private int getMaxRowSize(List<List<RectangularTextContainer>> rows) {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }

    private String removeExtraEmptyLines(String text) {
        return text.replaceAll("(\r\n){2,}", "\r\n");
    }

    private boolean isExtraBodyLinesContainsSimilarLine(String line) {
        return POTENTIAL_EXTRA_BODY_LINES.stream().anyMatch(ll -> levenshteinDistance.getDistance(ll, line.trim()) >= SIMILARITY_THRESHOLD);
    }

    private void appendHeaderSeparator(StringBuilder tableStr, int columnCount) {
        tableStr.append("|---".repeat(columnCount)).append("|\n");
    }

    private boolean isValidTable(Table table) {
        if (table.getRows().size() <= 1) {
            return false;
        }
        // Checking that the table contains more than one non-empty row
        // and at least one row contains more than one non-empty cell
        return table.getRows().stream().anyMatch(row -> !cleanRow(row).isEmpty());
//                && table.getRows().stream().anyMatch(row -> cleanRow(row).size() > 1);
    }

    @Getter
    private static class RectangleRegion extends Rectangle2D.Float {

        public RectangleRegion(float x, float y, float w, float h, String regionStr) {
            super(x, y, w, h);
            this.regionStr = regionStr;
        }

        private final String regionStr;

    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class LineInfo {
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
    private static class PdfTable {
        private float startX;
        private float startY;
        private float endX;
        private float endY;
        private String text;

        public PdfTable(float startX, float startY, float endX, float endY) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
        }
    }
}
