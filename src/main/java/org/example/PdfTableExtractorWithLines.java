package org.example;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfTableExtractorWithLines {

    private final List<Line2D> horizontalLines = new ArrayList<>();
    private final List<Line2D> verticalLines = new ArrayList<>();

    public void extractLinesFromPage(PDPage page, int pageNum) throws IOException {
        PDFLineExtractor lineExtractor = new PDFLineExtractor(pageNum);
        lineExtractor.processPage(page);
    }

    private class PDFLineExtractor extends PDFStreamEngine {
        private float startX, startY;
        private float endX, endY;
        private final int pageNum;

        public PDFLineExtractor(int pageNum) {
            this.pageNum = pageNum;

            addOperator(new OperatorProcessor() {
                @Override
                public void process(Operator operator, List<COSBase> operands) {
                    if (operands.size() == 2) {
                        startX = ((COSNumber) operands.get(0)).floatValue();
                        startY = ((COSNumber) operands.get(1)).floatValue();
                    }
                }

                @Override
                public String getName() {
                    return "m";  // "m" is the PDF operator for "move to"
                }
            });

            addOperator(new OperatorProcessor() {
                @Override
                public void process(Operator operator, List<COSBase> operands) {
                    if (operands.size() == 2) {
                        endX = ((COSNumber) operands.get(0)).floatValue();
                        endY = ((COSNumber) operands.get(1)).floatValue();
                        addLine(new Line2D.Float(startX, startY, endX, endY));
                    }
                }

                @Override
                public String getName() {
                    return "l";  // "l" is the PDF operator for "line to"
                }
            });

            addOperator(new OperatorProcessor() {
                @Override
                public void process(Operator operator, List<COSBase> operands) {
                    // Stroke path (complete the drawing)
                }

                @Override
                public String getName() {
                    return "S";  // "S" is the PDF operator for "stroke path"
                }
            });
        }

        private void addLine(Line2D line) {
            if (line.getP1().getY() == line.getP2().getY()) {
                // Горизонтальная линия
                horizontalLines.add(line);
            } else if (line.getP1().getX() == line.getP2().getX()) {
                // Вертикальная линия
                verticalLines.add(line);
            }
        }
    }

    public List<RectangleRegion> findTableRegions(PDPage page) throws IOException {
        List<RectangleRegion> tableRegions = new ArrayList<>();

        // Группируем линии и ищем замкнутые области (прямоугольники)
        for (Line2D hLine : horizontalLines) {
            for (Line2D vLine : verticalLines) {
                // Проверка на пересечение линий и создание прямоугольника
                if (hLine.intersectsLine(vLine)) {
                    float minX = (float) Math.min(hLine.getX1(), vLine.getX1());
                    float minY = (float) Math.min(hLine.getY1(), vLine.getY1());
                    float maxX = (float) Math.max(hLine.getX2(), vLine.getX2());
                    float maxY = (float) Math.max(hLine.getY2(), vLine.getY2());

                    RectangleRegion tableRegion = new RectangleRegion(minX, minY, maxX - minX, maxY - minY, page.getRotation());

                    // Извлекаем текст внутри этого региона
                    extractTextFromRegion(page, tableRegion);

                    tableRegions.add(tableRegion);
                }
            }
        }

        return tableRegions;
    }

    private void extractTextFromRegion(PDPage page, RectangleRegion region) throws IOException {
        PDFTextStripperByArea stripperByArea = new PDFTextStripperByArea();
        Rectangle2D.Float rect = new Rectangle2D.Float(region.x, region.y, region.width, region.height);
        stripperByArea.addRegion("region", rect);

        stripperByArea.extractRegions(page);

        String text = stripperByArea.getTextForRegion("region");
        region.setText(text);
    }

    // Класс для представления региона таблицы
    class RectangleRegion {
        float x, y, width, height;
        int pageNum;
        String text;

        public RectangleRegion(float x, float y, float width, float height, int pageNum) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.pageNum = pageNum;
            this.text = "";
        }

        public void setText(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return String.format("Region [page=%d, x=%.2f, y=%.2f, width=%.2f, height=%.2f, text=%s]",
                    pageNum, x, y, width, height, text);
        }
    }
}
