package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class PdfBox {
    public static void main(String[] args) {
        File file = new File("./files/Maples+Group+-+Transfer+Agent+FAQ+-+August+2023.pdf");
        try (PDDocument document = PDDocument.load(file)) {
            CustomPDFTextStripper pdfStripper = new CustomPDFTextStripper();
            pdfStripper.setSortByPosition(true);
            String pdfText = pdfStripper.getText(document);

            // Extract and print tables in Markdown format
            String markdownTable = extractTableToMarkdown(pdfText);
            System.out.println(markdownTable);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String extractTableToMarkdown(String pdfText) {
        StringBuilder markdown = new StringBuilder();

        // Simple logic to find table-like structures in the text
        String[] lines = pdfText.split("\n");
        for (String line : lines) {
            if (line.matches(".*\\|.*\\|.*")) {
                markdown.append(line).append("\n");
            }
        }

        // Adding header separator (assuming first line is the header)
        if (markdown.length() > 0) {
            int columnCount = markdown.toString().split("\n")[0].split("\\|").length;
            for (int i = 0; i < columnCount - 1; i++) {
                markdown.append("|---");
            }
            markdown.append("|\n");
        }

        return markdown.toString();
    }

    static class CustomPDFTextStripper extends PDFTextStripper {
        public CustomPDFTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            super.writeString(text, textPositions);
        }
    }
}
