package org.example;


import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;

public class Test {

    public String getPageText(PDDocument document) throws IOException {
        // Получаем форму из документа
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();

        // Если форма присутствует, выполняем flatten (плоское преобразование)
        if (acroForm != null) {
            acroForm.flatten();
        }

        // Теперь можно использовать PDFTextStripper для извлечения текста
        PDFTextStripper stripper = new PDFTextStripper();
        String extractedText = stripper.getText(document);

        return removeExtraEmptyLines(extractedText).trim() + "\n";
    }

    // Метод для удаления лишних пустых строк
    private String removeExtraEmptyLines(String text) {
        return text.replaceAll("(?m)^[ \t]*\r?\n", "");
    }

    public static void main(String[] args) {
        try {
            PDDocument document = PDDocument.load(new File("./test/Project_6030+section+1.pdf"));
            Test flattener = new Test();
            String text = flattener.getPageText(document);
            System.out.println(text);
            document.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}