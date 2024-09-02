package org.example;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class PdfWriter {
    public static void main(String[] args) throws IOException {
        File dir = new File("./test");
        File[] filesArray = dir.listFiles((dir1, name) -> name.toLowerCase().endsWith(".pdf"));
        List<String> files = new ArrayList<>();
        for (File file : filesArray) {
            files.add(file.getName());
        }
        Map<String, Boolean> testMap = new HashMap<>();
        String fileName = "Project_6030.pdf";
        if (fileName == null) {
            long start = System.currentTimeMillis();
            for (String file : files) {
                PDDocument document = PDDocument.load(new File("./test/" + file));
                File outputFile = new File("./output/" + file.replaceAll(".pdf", ".md"));
                try (FileWriter writer = new FileWriter(outputFile)) {

                    PdfTextExtractor pdfExtractor = new PdfTextExtractor(document);
                    testMap.put(file, pdfExtractor.isSselDocument());
                    List<FilePage> pages = pdfExtractor.extract(true);
                    for (FilePage page : pages) {
                        writer.write(page.getText());
                    }
                    document.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            long end = System.currentTimeMillis();
            System.out.println(fileName + "processed. Executed time: " + (double)(end - start)/1000 + "s");
            System.out.println(testMap);
        } else {
            PDDocument document = PDDocument.load(new File("./test/" + fileName));

//            test(document);
            File outputFile = new File("./output1/" + fileName.replaceAll(".pdf", ".md"));
            try(FileWriter writer = new FileWriter(outputFile)) {
                PdfTextExtractor pdfExtractor = new PdfTextExtractor(document);
                long start = System.currentTimeMillis();
                List<FilePage> pages  = pdfExtractor.extract(true);
                List<FileChunk> chunks = splitSselDocumentToChunks(pages, pdfExtractor.getQuestionStartPrefix());
                long end = System.currentTimeMillis();
                System.out.println(fileName + "processed. Executed time: " + (double)(end - start)/1000 + "s");
                for (FilePage page: pages) {
                    writer.write(page.getText());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public static List<FileChunk> splitSselDocumentToChunks(List<FilePage> filePages, String questionStartPrefix) {
        List<FileChunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int chunkStartPageNumber = -1;

        for (FilePage page : filePages) {
            String[] parts = page.getText().split(Pattern.quote(questionStartPrefix));

            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    if (currentChunk.length() > 0) {
                        currentChunk.append(parts[i]);
                    } else {
                        currentChunk.append(parts[i]);
                        chunkStartPageNumber = page.getPageNumber();
                    }
                } else {
                    if (chunkStartPageNumber != 1) {
                        chunks.add(new FileChunk(currentChunk.toString().trim(), chunkStartPageNumber));
                    }
                    currentChunk.setLength(0);
                    currentChunk.append(parts[i]);
                    chunkStartPageNumber = page.getPageNumber();
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(new FileChunk(currentChunk.toString().trim(), chunkStartPageNumber));
        }

        return chunks;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileChunk {
        private String text;
        private int pageNumber;
    }
}
