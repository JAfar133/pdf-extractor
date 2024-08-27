package org.example;
import java.awt.geom.Rectangle2D;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.*;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.*;
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
    private static final boolean tablesWithHeader = true;
    private final PDFTextStripperByArea stripperByArea;
    private final PDDocument document;
    private final ExtractionAlgorithm tableExtractionAlgorithm;
    private List<Field> notFlattenFields = new ArrayList<>();
    Map<String, Field> formData = new HashMap<>();

    public PdfTextExtractor(PDDocument document) throws IOException {
        this.document = document;
        this.stripperByArea = new PDFTextStripperByArea();
        this.tableExtractionAlgorithm = new SpreadsheetExtractionAlgorithm().withMinSpacingBetweenRulings(MIN_TABLE_CELL_HEIGHT_AND_WIDTH);
    }

    public List<FilePage> extract(boolean cleanPages) throws IOException {
        ObjectExtractor extractor = new ObjectExtractor(document);
        PageIterator pageIterator = extractor.extract();
        List<FilePage> filePages = new ArrayList<>();

        if (cleanPages) {
            // Determine the dimensions of the PDF page
            PDRectangle mediaBox = document.getPage(0).getMediaBox();
            List<RectangleRegion> regions = getRectangleRegions(mediaBox);

            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
            PDType0Font font = PDType0Font.load(document, new FileInputStream("C:/Windows/Fonts/Arial.ttf"), false);

            acroForm.getDefaultResources().put(COSName.getPDFName("F3"), font);

            int idCounter = 1;

            replaceButtonsWithTextFields(acroForm);
            for (PDField field : acroForm.getFields()) {
                String uniqueId = ".)-&f" + idCounter++;
                PDRectangle rectangle = field.getWidgets().get(0).getRectangle();
                float yFromTop = mediaBox.getHeight() - rectangle.getUpperRightY();

                if (field instanceof PDTextField && doesTextOverflow(field) ) {
                    formData.put(uniqueId, new Field(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), field.getValueAsString(), getFieldPageIndex(field)));
                    field.setValue(uniqueId);
                }
            }


            acroForm.flatten();
            Map<String, Set<RectangleRegion>> duplicates = findRepetitiveLinesAndPatterns(document, regions);

            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                filePages.add(new FilePage(getPdfPageText(document, page, duplicates), page.getPageNumber()));
            }
        } else {
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                filePages.add(new FilePage(getPdfPageText(document, page), page.getPageNumber()));
            }
        }

        return filePages;
    }

    public void replaceButtonsWithTextFields(PDAcroForm acroForm) throws IOException {
        int idCounter = 1;
        List<PDField> fieldsToRemove = new ArrayList<>();

        for (PDField field : acroForm.getFields()) {
            PDRectangle rectangle = field.getWidgets().get(0).getRectangle();

            String textFieldValue = "";

            if (field instanceof PDCheckBox) {
                PDCheckBox checkBox = (PDCheckBox) field;
                textFieldValue = checkBox.isChecked() ? "Checked" : "Unchecked";
                fieldsToRemove.add(field);  // Отмечаем для удаления после
            } else if (field instanceof PDRadioButton) {
                PDRadioButton radioButton = (PDRadioButton) field;
                textFieldValue = radioButton.getValueAsString();
                fieldsToRemove.add(field);  // Отмечаем для удаления после
            }

            // Создаем новое текстовое поле с таким же расположением
            PDTextField textField = new PDTextField(acroForm);
            textField.setPartialName("TextField_" + idCounter++);
            textField.setValue(textFieldValue);

            // Настраиваем виджет для нового текстового поля
            textField.getWidgets().get(0).setRectangle(rectangle);
            textField.getWidgets().get(0).setPage(field.getWidgets().get(0).getPage()); // Настраиваем страницу виджета

            // Добавляем новое текстовое поле в форму
            acroForm.getFields().add(textField);
        }

        // Удаление старых кнопок, после того как новые текстовые поля добавлены
        for (PDField field : fieldsToRemove) {
            acroForm.getFields().remove(field);
        }
    }

    private List<Field> convertToFields(List<PDField> fields, PDRectangle mediaBox) {
        return fields.stream().map(field -> {
            PDRectangle rectangle = field.getWidgets().get(0).getRectangle();
            float yFromTop = mediaBox.getHeight() - rectangle.getUpperRightY();
            if (field instanceof PDTextField) {
                try {
                    return new Field(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), field.getValueAsString(), getFieldPageIndex(field));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else if (field instanceof PDCheckBox) {
                PDCheckBox checkBox = (PDCheckBox) field;
                try {
                    return new Field(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), checkBox.isChecked() ? "Checked" : "Unchecked", getFieldPageIndex(field));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        }).collect(Collectors.toList());
    }

    private boolean doesTextOverflow(PDField field) {
        if (field instanceof PDTextField) {
            PDTextField textField = (PDTextField) field;

            // Получаем размер шрифта из DA строки
            float fontSize = getFontSizeFromDA(textField.getDefaultAppearance());
            if (fontSize == 0) {
                fontSize = 12;
            }

            float leading = fontSize * 1.2f;

            PDRectangle widgetRectangle = textField.getWidgets().get(0).getRectangle();
            float widgetWidth = widgetRectangle.getWidth();
            float widgetHeight = widgetRectangle.getHeight();

            // Получаем текст из поля
            String[] lines = textField.getValueAsString().split("\n");

            int numberOfLines = 0;

            // Рассчитываем ширину каждой строки текста
            for (String line : lines) {
                float lineWidth = getTextWidth(line, fontSize);
                if (lineWidth > widgetWidth) {
                    // Если строка слишком длинная, разбиваем ее на несколько строк
                    numberOfLines += Math.ceil(lineWidth / widgetWidth);
                } else {
                    numberOfLines++;
                }
            }

            // Рассчитываем общую высоту текста с учетом межстрочного интервала
            float textHeight = numberOfLines * leading;

            // Проверяем, превышает ли высота текста высоту виджета
            return textHeight > widgetHeight + 1.2f;
        }
        return false;
    }

    private float getTextWidth(String text, float fontSize) {
        // Здесь можно использовать примерное значение ширины символов
        // В идеале лучше использовать метод для точного расчета ширины текста с учетом конкретного шрифта
        float averageCharWidth = fontSize * 0.5f; // Примерная ширина символа
        return text.length() * averageCharWidth;
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
    private String getPdfPageText(PDDocument document, Page page) {
        PDFTextStripper pdfStripper = new PDFTextStripper();
        pdfStripper.setStartPage(page.getPageNumber());
        pdfStripper.setEndPage(page.getPageNumber());
        return pdfStripper.getText(document);
    }

    @SneakyThrows
    private String getPdfPageText(PDDocument document, Page page, Map<String, Set<RectangleRegion>> duplicates) {
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

    private boolean isFieldOnPage(PDField field, int pageIndex) throws IOException {
        return getFieldPageIndex(field) == pageIndex;
    }

    private Field extractField(PDField field, PDRectangle mediaBox) throws IOException {
        PDRectangle rectangle = field.getWidgets().get(0).getRectangle();
        float yFromTop = mediaBox.getHeight() - rectangle.getUpperRightY();
        if (field instanceof PDTextField) {
            return new Field(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), field.getValueAsString(), getFieldPageIndex(field));
        } else if (field instanceof PDCheckBox) {
            PDCheckBox checkBox = (PDCheckBox) field;
            return new Field(rectangle.getLowerLeftX(), yFromTop, rectangle.getWidth(), rectangle.getHeight(), checkBox.isChecked() ? "Checked" : "Unchecked", getFieldPageIndex(field));
        }

        return null;
    }

    @Data
    @AllArgsConstructor
    private static class Field {
        private float x;
        private float y;
        private float width;
        private float height;
        private String text;
        private int pageIndex;
    }

    private Map<String, Set<RectangleRegion>> findRepetitiveLinesAndPatterns(PDDocument document, List<RectangleRegion> regions) throws IOException {
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
                appendRowToTableString(tableStr, row, page, tableFormDatas);
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

    private void appendRowToTableString(
            StringBuilder tableStr,
            List<RectangularTextContainer> row,
            Page page,
            List<String> tableFormDatas) throws IOException {
        for (RectangularTextContainer cell : row) {
            String formKey = getFormKeyInsideCell(cell, page.getPageNumber());
            if (formKey != null) {
                tableFormDatas.add(formKey);
                tableStr.append("| ").append(formData.get(formKey).getText().replaceAll("\\r?\\n", " ")).append(" ");
            } else {
                String cellText = getTextByTextArea(cell, page).trim().replaceAll("\\r?\\n", " ");
                for (String key: tableFormDatas) {
                    if (cellText.contains(key)) {
                        cellText.replace(key, "");
                    }
                }
                tableStr.append("| ").append(cellText).append(" ");
            }
        }
        tableStr.append("|\n");
    }

    private boolean isCellIntersect(RectangularTextContainer currentCell, RectangularTextContainer prevCell) {
        if (currentCell == prevCell) return false;

        return prevCell.getX() + prevCell.getWidth() - 1 > currentCell.getX();
    }

    private String getFormKeyInsideCell(RectangularTextContainer cell, int pageNumber) {

        for (String key: formData.keySet()) {
            Field field = formData.get(key);
            if (field.getPageIndex() == pageNumber - 1 && cell.getX() <= field.getX()
                    && cell.getY() <= field.getY()
                    && (cell.getX() + cell.getWidth()) >= (field.getX() + field.getWidth())
                    && (cell.getY() + cell.getHeight()) >= (field.getY() + field.getHeight())) {
                return key;
            }
        }
        return null;
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


    // Check if all cells are in the same row and in height
    private boolean isCellsInOneRow(List<RectangularTextContainer> cells) {
        List<RectangularTextContainer> filteredCells = filterRow(cells);
        if (filteredCells.size() <= 1) {
            return true;
        }
        double firstY = filteredCells.get(0).getY();
        double firstHeight = filteredCells.get(0).getHeight();
        return filteredCells.stream().allMatch(cell ->
                cell.getY() == firstY && cell.getHeight() == firstHeight
        );
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

    // Exclude TextChunk cells
    private List<RectangularTextContainer> filterRow(List<RectangularTextContainer> row) {
        return row.stream().filter(cell -> cell instanceof Cell).collect(Collectors.toList());
    }

    private void appendHeaderSeparator(StringBuilder tableStr, int columnCount) {
        tableStr.append("|---".repeat(columnCount)).append("|\n");
    }

    private float getRowWidth(List<RectangularTextContainer> row) {
        return (float) row.stream().mapToDouble(RectangularTextContainer::getWidth).sum();
    }

    private boolean isValidTable(Table table) {
        if (table.getRows().size() <= 1) {
            return false;
        }
        // Checking that the table contains more than one non-empty row
        // and at least one row contains more than one non-empty cell
        return table.getRows().stream().filter(row -> !cleanRow(row).isEmpty()).count() > 1
                && table.getRows().stream().anyMatch(row -> cleanRow(row).size() > 1);
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
