package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class PdfWriter {
    public static void main(String[] args) throws IOException {
        String fileName = "Maples+Group+-+Transfer+Agent+FAQ+-+August+2023.pdf";
        PDDocument document = PDDocument.load(new File("./files/" + fileName));
        File outputFile = new File("./output/" + fileName.replaceAll(".pdf", ".md"));
        try(FileWriter writer = new FileWriter(outputFile)) {

            PdfTextExtractor pdfExtractor = new PdfTextExtractor();
            List<FilePage> pages  = pdfExtractor.extract(document, true);
            for (FilePage page: pages) {
                writer.write(page.getText());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
