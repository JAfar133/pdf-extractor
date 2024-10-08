package org.example;

import technology.tabula.*;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Tabula {
    public static void main(String[] args) {
        File file = new File("./files/Maples+Group+-+Transfer+Agent+FAQ+-+August+2023.pdf");
        File outputFile = new File("test.md");
        try (PDDocument document = PDDocument.load(file);
             FileWriter writer = new FileWriter(outputFile)) {

            ObjectExtractor extractor = new ObjectExtractor(document);
            PageIterator pageIterator = extractor.extract();

            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                int pageNumber = page.getPageNumber();
                SpreadsheetExtractionAlgorithm algorithm = new SpreadsheetExtractionAlgorithm();
                List<Table> tables = algorithm.extract(page);

                for (Table table : tables) {
                    if (pageNumber == 8) {
                        writer.write("## Page: " + pageNumber + "\n");
                        writer.write(convertTableToMarkdown(table));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String convertTableToMarkdown(Table table) {
        StringBuilder markdown = new StringBuilder();

        // Extract rows
        List<List<RectangularTextContainer>> rows = table.getRows();

        // Determine the number of columns based on the first row
        List<RectangularTextContainer> firstRow = new ArrayList<>();
        boolean isFirstRow = false;
        // Process each row
        for (int i = 0; i < rows.size(); i++) {
            List<RectangularTextContainer> cells = cleanColumns(rows.get(i));

            if (firstRow.isEmpty() && !cells.isEmpty()) {
                firstRow = cells;
                isFirstRow = true;
            }
            if (cells.size() > 1) {
                // Convert row to Markdown
                for (int j = 0; j < cells.size(); j++) {
                    RectangularTextContainer currentCell = cells.get(j);
                    // Concatenate text fragments inside the same cell
                    String cellText = currentCell.getText().trim().replaceAll("\\r", " ");
                    if (j >= firstRow.size()) {
                        markdown.append(" ").append(cellText);
                    } else {
                        markdown.append("| ").append(cellText).append(" ");
                    }
                }
                markdown.append("|\n");

                // Add header separator after the first row
                if (isFirstRow) {
                    markdown.append("|---".repeat(cells.size()));
                    isFirstRow = false;
                    markdown.append("|\n");
                }
            }
        }

        return markdown.toString();
    }

    private static List<RectangularTextContainer> cleanColumns(List<RectangularTextContainer> cells) {
        List<RectangularTextContainer> cleanColumns = new ArrayList<>();
        boolean visitText = false;
        for (int i = 0; i < cells.size(); i++) {
            String text = cells.get(i).getText().trim();
            if (text.isEmpty() && !visitText) {
                cleanColumns.add(cells.get(i));
            }
            if (!text.isEmpty()) {
                visitText = true;
                cleanColumns.add(cells.get(i));
            }
        }
        if (!visitText) {
            return Collections.emptyList();
        }
        return cleanColumns;
    }
}
