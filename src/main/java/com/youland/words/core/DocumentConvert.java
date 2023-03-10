/*
 Copyright (C) 2018-2021 YouYu information technology (Shanghai) Co., Ltd.
 <p>
 All right reserved.
 <p>
 This software is the confidential and proprietary
 information of YouYu Company of China.
 ("Confidential Information"). You shall not disclose
 such Confidential Information and shall use it only
 in accordance with the terms of the contract agreement
 you entered into with YouYu inc.
*/
package com.youland.words.core;

import com.aspose.words.FontSettings;
import com.aspose.words.PageRange;
import com.aspose.words.PageSet;
import com.aspose.words.PdfSaveOptions;
import com.google.common.collect.Lists;
import com.spire.doc.Document;
import com.spire.doc.FieldType;
import com.spire.doc.FileFormat;
import com.spire.doc.HeaderFooter;
import com.spire.doc.Section;
import com.spire.doc.documents.BreakType;
import com.spire.doc.documents.MarginsF;
import com.spire.doc.documents.Paragraph;
import com.spire.doc.documents.XHTMLValidationType;
import com.spire.doc.fields.TextRange;
import com.youland.words.model.DocumentHtmlAndFooter;
import com.youland.words.model.DocumentHtmlsAndFooter;
import com.youland.words.utils.SystemUtil;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;
import org.jsoup.helper.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.CollectionUtils;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentConvert {

  private static Logger logger = LoggerFactory.getLogger(DocumentConvert.class);

  private static final ThreadPoolExecutor threadPoolExecutor =
      new ThreadPoolExecutor(
          20,
          100,
          60,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>(40),
          new ThreadPoolExecutor.CallerRunsPolicy());

  /**
   * @param is the is html InputStream
   * @return ByteArrayResource
   */
  public static ByteArrayResource generateWord(InputStream is) {
    Document doc = new Document();
    doc.loadFromStream(is, FileFormat.Html, XHTMLValidationType.None);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    doc.saveToFile(out, FileFormat.Docx_2013);
    return removeLogo(new ByteArrayResource(out.toByteArray()));
  }
  /**
   * generate doc by html.
   *
   * @param html word html template
   * @return ByteArrayResource
   */
  public static ByteArrayResource generateWord(String html) {

    Document doc = new Document();
    doc.loadFromStream(
        new ByteArrayInputStream(html.getBytes()), FileFormat.Html, XHTMLValidationType.None);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    doc.saveToFile(out, FileFormat.Docx_2013);
    return removeLogo(new ByteArrayResource(out.toByteArray()));
  }

  /**
   * @param htmlAndFooters word html template
   * @return ByteArrayResource
   */
  public static ByteArrayResource generateWord(List<DocumentHtmlAndFooter> htmlAndFooters)
      throws Exception {

    if (CollectionUtils.isEmpty(htmlAndFooters)) {
      throw new ValidationException("No documents");
    }
    // first document
    CompletableFuture<ByteArrayResource> firstFuture =
        CompletableFuture.supplyAsync(
            () -> {
              DocumentHtmlAndFooter first = htmlAndFooters.get(0);
              MarginsF margins = new MarginsF(36, 36, 36, 72);
              ByteArrayOutputStream firstOut = generateWord(first, margins);
              ByteArrayResource firstResource =
                  removeLogo(new ByteArrayResource(firstOut.toByteArray()));
              return firstResource;
            },
            threadPoolExecutor);
    // add other documents
    List<CompletableFuture<ByteArrayResource>> featureList = Lists.newArrayList(firstFuture);
    CompletableFuture[] cfArray = new CompletableFuture[htmlAndFooters.size()];
    for (int i = 1; i < htmlAndFooters.size(); i++) {
      DocumentHtmlAndFooter item = htmlAndFooters.get(i);
      CompletableFuture<ByteArrayResource> future =
          CompletableFuture.supplyAsync(
              () -> {
                ByteArrayOutputStream pdf = generateWord(item);
                ByteArrayResource byteArrayResource =
                    removeLogo(new ByteArrayResource(pdf.toByteArray()));
                return byteArrayResource;
              },
              threadPoolExecutor);
      featureList.add(future);
    }
    CompletableFuture.allOf(featureList.toArray(cfArray)).join();

    // append documents
    Document document = new Document(firstFuture.get().getInputStream());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 1; i < featureList.size(); i++) {
      InputStream append = featureList.get(i).get().getInputStream();
      document.insertTextFromStream(append, FileFormat.Docx_2013);
    }
    document.saveToFile(out, FileFormat.Docx_2013);
    return removeLogo(new ByteArrayResource(out.toByteArray()));
  }

  /**
   * generateWord By SubHtml
   * @param htmlListAndFooters
   * @return
   * @throws Exception
   */
  public static ByteArrayResource generateWordBySubHtml(List<DocumentHtmlsAndFooter> htmlListAndFooters)
          throws Exception {

    if (CollectionUtils.isEmpty(htmlListAndFooters)) {
      throw new ValidationException("No documents");
    }
    // first document
    CompletableFuture<ByteArrayResource> firstFuture =
        CompletableFuture.supplyAsync(
            () -> {
              MarginsF margins = new MarginsF(36, 36, 36, 72);
              ByteArrayResource mainAndSub = null;
              DocumentHtmlsAndFooter first = htmlListAndFooters.get(0);
              if (CollectionUtils.isEmpty(first.getDocumentHtmls())) {
                throw new ValidationException("No documents");
              }
              List<String> htmlList = first.getDocumentHtmls();
              var main = generateWord(htmlList.get(0));
              mainAndSub = main;
              for (int n = 1; n < htmlList.size(); n++) {
                var sub = generateWord(htmlList.get(n));
                mainAndSub = appendDoc(main, sub);
              }

              var firstResource = generateWord(mainAndSub, first.getFooter(), margins);
              return removeLogo(new ByteArrayResource(firstResource.toByteArray()));
            },
            threadPoolExecutor);
    // add other documents
    List<CompletableFuture<ByteArrayResource>> featureList = Lists.newArrayList(firstFuture);
    CompletableFuture[] cfArray = new CompletableFuture[htmlListAndFooters.size()];
    for (int i = 1; i < htmlListAndFooters.size(); i++) {
      DocumentHtmlsAndFooter item = htmlListAndFooters.get(i);
      CompletableFuture<ByteArrayResource> future =
              CompletableFuture.supplyAsync(
                      () -> {
                        ByteArrayResource mainAndSub = null;
                        List<String> htmlList = item.getDocumentHtmls();
                        Footer footer = item.getFooter();
                        if (CollectionUtils.isEmpty(htmlList)) {
                          throw new ValidationException("No documents");
                        }
                        var main = generateWord(htmlList.get(0));
                        mainAndSub = main;
                        for (int n = 1; n < htmlList.size(); n++) {
                          var sub = generateWord(htmlList.get(n));
                          mainAndSub = appendDoc(main, sub);
                        }

                        ByteArrayOutputStream subResource = generateWord(mainAndSub, footer);
                        return removeLogo(new ByteArrayResource(subResource.toByteArray()));
                      },
                      threadPoolExecutor);
      featureList.add(future);
    }
    CompletableFuture.allOf(featureList.toArray(cfArray)).join();

    // append documents
    Document document = new Document(firstFuture.get().getInputStream());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 1; i < featureList.size(); i++) {
      InputStream append = featureList.get(i).get().getInputStream();
      document.insertTextFromStream(append, FileFormat.Docx_2013);
    }
    document.saveToFile(out, FileFormat.Docx_2013);
    return removeLogo(new ByteArrayResource(out.toByteArray()));
  }

  /**
   * default margins
   * @param docHtmlAndFooter
   * @return
   */
  private static ByteArrayOutputStream generateWord(DocumentHtmlAndFooter docHtmlAndFooter) {
    MarginsF margins = new MarginsF(72, 72, 72, 72);
    return generateWord(docHtmlAndFooter, margins);
  }

  /**
   * default margins
   * @param byteArrayResource
   * @return
   */
  private static ByteArrayOutputStream generateWord(ByteArrayResource byteArrayResource, Footer footer) {
    MarginsF margins = new MarginsF(72, 72, 72, 72);
    return generateWord(byteArrayResource, footer, margins);
  }

  /**
   * custom margins
   * @param byteArrayResource
   * @param margins
   * @return
   */
  private static ByteArrayOutputStream generateWord(ByteArrayResource byteArrayResource, Footer docFooter, MarginsF margins) {

    Document document = new Document();
    document.loadFromStream(new ByteArrayInputStream(byteArrayResource.getByteArray()), FileFormat.Docm_2013);
    // add footer
    Section section = document.getSections().get(0);
    section.getPageSetup().setFooterDistance(15f);
    // get footer
    HeaderFooter footer = section.getHeadersFooters().getFooter();
    Paragraph footerParagraph = footer.addParagraph();
    section.getPageSetup().setRestartPageNumbering(true);
    section.getPageSetup().setPageStartingNumber(1);
    //set margins
    section.getPageSetup().getMargins().setTop(margins.getTop());
    section.getPageSetup().getMargins().setBottom(margins.getBottom());
    section.getPageSetup().getMargins().setLeft(margins.getLeft());
    section.getPageSetup().getMargins().setRight(margins.getRight());
    // set footer information
    TextRange first = footerParagraph.appendText(Optional.ofNullable(docFooter.getTitle()).orElse("").concat(" - Page "));
    TextRange second = footerParagraph.appendField("page number", FieldType.Field_Page);
    TextRange third = footerParagraph.appendText(" of ");
    TextRange fourth = footerParagraph.appendText(String.valueOf(document.getPageCount()));
    footerParagraph.appendBreak(BreakType.Line_Break);
    TextRange fifth = footerParagraph.appendText("Loan ID: ".concat(docFooter.getLoanId()));
    footerParagraph.appendBreak(BreakType.Line_Break);
    TextRange sixth =
            footerParagraph.appendText("Property Address: ".concat(docFooter.getAddress()));
    first.getCharacterFormat().setFontSize(10f);
    second.getCharacterFormat().setFontSize(10f);
    third.getCharacterFormat().setFontSize(10f);
    fourth.getCharacterFormat().setFontSize(10f);
    fifth.getCharacterFormat().setFontSize(10f);
    first.getCharacterFormat().setFontSize(10f);
    sixth.getCharacterFormat().setFontSize(10f);
    // set the location
    footerParagraph.getFormat().setLeftIndent(-20);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.saveToFile(out, FileFormat.Docx_2013);

    return out;
  }

  /**
   * custom margins
   * @param docHtmlAndFooter
   * @param margins
   * @return
   */
  private static ByteArrayOutputStream generateWord(DocumentHtmlAndFooter docHtmlAndFooter, MarginsF margins) {

    String htmlContent = docHtmlAndFooter.getDocumentHtml();
    Footer docFooter = docHtmlAndFooter.getFooter();
    Document document = new Document();
    document.loadFromStream(
            new ByteArrayInputStream(htmlContent.getBytes()),
            FileFormat.Html,
            XHTMLValidationType.None);
    // add footer
    Section section = document.getSections().get(0);
    section.getPageSetup().setFooterDistance(15f);
    // get footer
    HeaderFooter footer = section.getHeadersFooters().getFooter();
    Paragraph footerParagraph = footer.addParagraph();
    section.getPageSetup().setRestartPageNumbering(true);
    section.getPageSetup().setPageStartingNumber(1);
    //set margins
    section.getPageSetup().getMargins().setTop(margins.getTop());
    section.getPageSetup().getMargins().setBottom(margins.getBottom());
    section.getPageSetup().getMargins().setLeft(margins.getLeft());
    section.getPageSetup().getMargins().setRight(margins.getRight());
    // set footer information
    TextRange first = footerParagraph.appendText(docFooter.getTitle().concat(" - Page "));
    TextRange second = footerParagraph.appendField("page number", FieldType.Field_Page);
    TextRange third = footerParagraph.appendText(" of ");
    TextRange fourth = footerParagraph.appendText(String.valueOf(document.getPageCount()));
    footerParagraph.appendBreak(BreakType.Line_Break);
    TextRange fifth = footerParagraph.appendText("Loan ID: ".concat(docFooter.getLoanId()));
    footerParagraph.appendBreak(BreakType.Line_Break);
    TextRange sixth =
            footerParagraph.appendText("Property Address: ".concat(docFooter.getAddress()));
    first.getCharacterFormat().setFontSize(10f);
    second.getCharacterFormat().setFontSize(10f);
    third.getCharacterFormat().setFontSize(10f);
    fourth.getCharacterFormat().setFontSize(10f);
    fifth.getCharacterFormat().setFontSize(10f);
    first.getCharacterFormat().setFontSize(10f);
    sixth.getCharacterFormat().setFontSize(10f);
    // set the location
    footerParagraph.getFormat().setLeftIndent(-20);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.saveToFile(out, FileFormat.Docx_2013);

    return out;
  }


  /**
   * append docx
   *
   * @param src
   * @param append append
   * @return
   */
  public static ByteArrayResource appendDoc(ByteArrayResource src, ByteArrayResource append) {

    Document document = new Document(new ByteArrayInputStream(src.getByteArray()));
    document.setKeepSameFormat(false);
    document.insertTextFromStream(
            new ByteArrayInputStream(append.getByteArray()), FileFormat.Docx_2013);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.saveToFile(out, FileFormat.Docx_2013);

    return removeLogo(new ByteArrayResource(out.toByteArray()));
  }

  /**
   *
   * @param htmlContent html
   * @return ByteArrayOutputStream pdf
   */
  public static ByteArrayOutputStream htmlToPdf(String htmlContent) {

    org.jsoup.nodes.Document document = Jsoup.parse(htmlContent, "UTF-8");
    document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
    try {
      // default size: 10 kB
      ByteArrayOutputStream fileOutputStream = new ByteArrayOutputStream(102400);
      ITextRenderer renderer = new ITextRenderer();
      renderer.setDocumentFromString(document.html());
      renderer.layout();
      renderer.createPDF(fileOutputStream, false);
      renderer.finishPDF();

      return fileOutputStream;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @param docResource word
   * @return ByteArrayOutputStream pdf
   */
  public static ByteArrayOutputStream docxToPdf(ByteArrayResource docResource) {
    try {

      com.aspose.words.Document document =
          new com.aspose.words.Document(docResource.getInputStream());

      logger.warn("SystemName: {}, isLinux???{}.",SystemUtil.getSystemName(), SystemUtil.isLinux());
      if(SystemUtil.isLinux()){
        FontSettings fontSettings = FontSettings.getDefaultInstance();
        fontSettings.setFontsFolder("/usr/share/fonts/", true);
        document.setFontSettings(fontSettings);
      }
      PdfSaveOptions options = new PdfSaveOptions();
      PageSet pageSet = new PageSet(new PageRange(0, document.getPageCount()));
      options.setPageSet(pageSet);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out, options);

      return out;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static ByteArrayResource removeLogo(ByteArrayResource byteArrayResource) {

    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      XWPFDocument doc = new XWPFDocument(OPCPackage.open(byteArrayResource.getInputStream()));
      List<XWPFParagraph> paragraphs = doc.getParagraphs();

      if (paragraphs.size() < 1) return byteArrayResource;
      XWPFParagraph firstParagraph = paragraphs.get(0);
      if (firstParagraph.getText().contains("Spire.Doc")) {
        doc.removeBodyElement(doc.getPosOfParagraph(firstParagraph));
      }
      doc.write(out);
      return new ByteArrayResource(out.toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  private static void removeText(PDPage page, boolean isFirstPage) throws IOException {

    List<String> firstWaterTexts = Lists.newArrayList();
    AtomicInteger with = new AtomicInteger(1);
    PDFStreamParser parser = new PDFStreamParser(page);
    parser.parse();
    List<?> tokens = parser.getTokens();
    for (int j = 0; j < tokens.size(); j++) {
      Object next = tokens.get(j);
      if (next instanceof Operator) {
        Operator op = (Operator) next;

        if (op.getName().equals("Tj") || op.getName().equals("Tj") || "".equals(op.getName())) {
          COSString previous = (COSString) tokens.get(j - 1);
          String string = previous.getString();
          if (string.contains("Spire.Doc")) {
            previous.setValue("".getBytes());
          } else if (isFirstPage && firstWaterTexts.stream().anyMatch(e -> string.equals(e))) {
            if (string.equals(firstWaterTexts.get(6)) && with.get() == 1) {
              with.incrementAndGet();
              previous.setValue("".getBytes());
            } else if (!string.equals(firstWaterTexts.get(6))) {
              previous.setValue("".getBytes());
            }
          }
        }
      }
    }
    List<PDStream> contents = new ArrayList<>();
    Iterator<PDStream> streams = page.getContentStreams();
    while (streams.hasNext()) {
      PDStream updatedStream = streams.next();
      OutputStream out = updatedStream.createOutputStream(COSName.FLATE_DECODE);
      ContentStreamWriter tokenWriter = new ContentStreamWriter(out);
      tokenWriter.writeTokens(tokens);
      contents.add(updatedStream);
      out.close();
    }
    page.setContents(contents);
  }

  private static List<String> getPageCosstring(InputStream inputStream) {

    List<String> costrings = org.apache.commons.compress.utils.Lists.newArrayList();
    try {
      PDDocument pdDocument = PDDocument.load(inputStream);
      PDPageTree pdPageTree = pdDocument.getPages();

      Iterator<PDPage> pageIterator = pdPageTree.iterator();
      PDFStreamParser parser = new PDFStreamParser(pageIterator.next());
      parser.parse();
      List<?> tokens = parser.getTokens();
      for (int j = 0; j < tokens.size(); j++) {
        Object next = tokens.get(j);
        if (next instanceof Operator) {
          Operator op = (Operator) next;

          if (op.getName().equals("Tj") || op.getName().equals("Tj") || "".equals(op.getName())) {
            COSString previous = (COSString) tokens.get(j - 1);
            String string = previous.getString();
            costrings.add(string);
          }
        }
      }
      pdDocument.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return costrings;
  }
}
