package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.IOException;

public class PdfBox {
    public static void main(String[] args) {

        File inputFile = new File("./test/Project_6030+section+1.pdf");
        File outputFile = new File("output.pdf");

        try {
            PDDocument document = PDDocument.load(inputFile);

            if (document.getNumberOfPages() >= 8) {
                PDDocument newDocument = new PDDocument();

                PDPage page = document.getPage(5);
                PDPage page1 = document.getPage(6);

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
