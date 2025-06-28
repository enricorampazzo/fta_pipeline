package ai.jurisgraph.etl.fta_pipeline;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
@Component
public class Main implements CommandLineRunner {
    @Value("${spring.ai.openai.chat.options.temperature}")
    private double temperature;
    @Value("${spring.ai.openai.chat.options.model}")
    private String model;
    private final ChatClient chatClient;
    private final Pattern markdownPattern = Pattern.compile("```(markdown)?\\n?");

    public Main(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public void run(String... args) throws Exception {
        model = model.replace("/", "-");
        String format = "png";
        String documentName = "Cabinet-Decision-No-142-of-2024-on-Top-up-Tax-on-MNEs";
        String pdf = "%s.pdf".formatted(documentName);
        PDFParser pdfParser = new PDFParser(new RandomAccessReadBuffer(new DefaultResourceLoader().getResource(pdf).getInputStream()));
        String filenameTemplate = "page_%d-%d_dpi.%s";
        List<Media> imageMedia = null;
        int dpi = 450;
        MimeType imageMt = MimeType.valueOf("image/png");
        int numberOfPages = 0;
        Path documentFolder = Path.of("output", documentName);
        Path renderedPagesFolder = documentFolder.resolve("renderedPages");
        Path markdownPages = documentFolder.resolve("markdownPages");
        Path outputFile = documentFolder.resolve("%s-model_%s-dpi_%d-%s.md".formatted(documentName, model, dpi, format));
        if(Files.notExists(documentFolder)){
            Files.createDirectories(documentFolder);
        }
        if(Files.notExists(renderedPagesFolder)){
           Files.createDirectories(renderedPagesFolder);
        }
        if(Files.notExists(markdownPages)){
            Files.createDirectories(markdownPages);
        }
        try (PDDocument pdfDocument = pdfParser.parse()) {
            PDFRenderer pdfRenderer = new PDFRenderer(pdfDocument);
            numberOfPages = pdfDocument.getNumberOfPages();

            imageMedia = IntStream.range(0, numberOfPages).boxed().toList().stream().map(pageNum -> {
                BufferedImage image = null;
                try {
                    image = pdfRenderer.renderImageWithDPI(pageNum, dpi, ImageType.RGB);
                    Path path = renderedPagesFolder.resolve(Path.of(String.format(filenameTemplate, pageNum + 1, dpi,format)));
                    File destination = null;
                    if(Files.notExists(path)) {
                        destination = Files.createFile(path).toFile();
                        ImageIO.write(image, format, destination);
                    }
                    else{
                        System.out.printf("%s already exists, skipping creation%n", path);
                        destination = path.toFile();
                    }
                    return Media.builder().mimeType(imageMt)
                            .data(Files.readAllBytes(destination.toPath())).name(destination.getName()).build();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }).toList();
            String prompt = """
                    Convert the law articles contained in the images to markdown. Headers, lists and tables must be
                    converted to markdown.
                    Mathematical formulas must be converted to markdown-friendly LaTeX.
                    For ease of processing, please only return markdown without any reasoning or comments.
                    If the image contains a list of definitions, convert them to a markdown table where one column is
                    the term defined and the other is the explanation of the term.
                    """;
            imageMedia.forEach(m -> {
                Path markDownPageFile = markdownPages.resolve(m.getName().replace("."+format, ".md"));

                try {
                    if (Files.exists(markDownPageFile) && Files.size(markDownPageFile)>0) {
                        System.out.printf("markdown file for %s has already been created, skipping%n", m.getName());
                        return;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(markDownPageFile.toFile()))) {
                    System.out.printf("Now processing %s%n", m.getName());
                    String response = Optional.ofNullable(chatClient.prompt(new Prompt(UserMessage.builder()
                            .text(prompt).media(m).build())).call().content()).orElse("");
                    System.out.print(response);
                    writer.write(markdownPattern.matcher(response).replaceAll(""));
                    writer.write(System.lineSeparator());
                    writer.flush();
                    Thread.sleep(Duration.of(2, ChronoUnit.SECONDS));
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }

            });
        }

        if(!outputFile.toFile().exists() || Files.size(outputFile) == 0){
            try(BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
                IntStream.range(0, numberOfPages).forEach(n -> {
                    try {
                        bw.write(Files.readString(markdownPages.resolve(Path.of("page_%d-%d_dpi.md".formatted(n, dpi)))));
                        bw.flush();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }

        System.exit(0);
    }
}
