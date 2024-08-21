package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class PdfBox {
    public static void main(String[] args) {

        File inputFile = new File("./files/Maples+Group+-+Transfer+Agent+FAQ+-+August+2023.pdf");
        File outputFile = new File("output.pdf");

        try {
            PDDocument document = PDDocument.load(inputFile);

            if (document.getNumberOfPages() >= 8) {
                PDDocument newDocument = new PDDocument();

                PDPage page = document.getPage(7);
                PDPage page1 = document.getPage(8);

                newDocument.addPage(page);
                newDocument.addPage(page1);

                newDocument.save(outputFile);

                newDocument.close();
            } else {
                System.out.println("Документ содержит менее 8 страниц.");
            }

            document.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
