import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
    static final int THREADS = 4;
    static final long DELAY_MS = 1000;
    static final long MEASURE_MS = 10_000;

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("What do you want to sort?");
        System.out.println("strings or file?");
        String choice = reader.readLine();
        if (choice == null) {
            return;
        }
        choice = choice.trim().toLowerCase();
        boolean fromFile;
        if (choice.equals("strings") || choice.equals("1")) {
            fromFile = false;
        } else if (choice.equals("file") || choice.equals("2")) {
            fromFile = true;
        } else {
            System.err.println("strings or file?");
            System.exit(1);
            return;
        }

        List<String> lines;
        String source = null;
        if (fromFile) {
            System.out.println("file?");
            String name = reader.readLine();
            if (name == null || name.trim().isEmpty()) {
                System.err.println("no file");
                System.exit(1);
                return;
            }
            source = name.trim();
            Path path = Path.of(source);
            if (!Files.isRegularFile(path)) {
                System.err.println("no file: " + source);
                System.exit(1);
                return;
            }
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } else {
            System.out.println("strings, end with \\");
            lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.equals("\\")) {
                lines.add(line);
            }
        }

        System.out.println(THREADS + " threads, delay " + DELAY_MS + " ms, measure " + (MEASURE_MS / 1000) + " s");
        System.out.println("LinkedSorter...");
        long linked = measureLinked(lines);
        System.out.println("ArrayList...");
        long array = measureArray(lines);
        String text = report(fromFile, source, lines, linked, array);
        System.out.print(text);
        appendReport(text);
    }

    static long measureLinked(List<String> lines) throws InterruptedException {
        LinkedSorter.Chain chain = new LinkedSorter.Chain();
        for (String line : lines) {
            LinkedSorter.addLine(chain, line);
        }
        AtomicLong steps = new AtomicLong();
        for (int i = 0; i < THREADS; i++) {
            Thread worker = new Thread(() -> LinkedSorter.sort(chain, DELAY_MS, steps));
            worker.setDaemon(true);
            worker.start();
        }
        Thread.sleep(MEASURE_MS);
        return steps.get();
    }

    static long measureArray(List<String> lines) throws InterruptedException {
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        for (String line : lines) {
            ArraySorter.addLine(list, line);
        }
        AtomicLong steps = new AtomicLong();
        for (int i = 0; i < THREADS; i++) {
            Thread worker = new Thread(() -> ArraySorter.sort(list, DELAY_MS, steps));
            worker.setDaemon(true);
            worker.start();
        }
        Thread.sleep(MEASURE_MS);
        return steps.get();
    }

    static String report(boolean fromFile, String source, List<String> lines, long linked, long array) {
        StringBuilder out = new StringBuilder();
        long linkedEstimate = THREADS * MEASURE_MS / (2 * DELAY_MS);
        long arrayEstimate = MEASURE_MS / DELAY_MS;
        out.append(fromFile ? "case: file\n" : "case: strings\n");
        out.append("\tLinkedSorter: ").append(linked).append('\n');
        out.append("\tArrayList: ").append(array).append('\n');
        out.append("\testimate:\n");
        out.append("\tLinkedSorter: ").append(linkedEstimate).append('\n');
        out.append("\tArrayList: ").append(arrayEstimate).append('\n');
        if (fromFile) {
            out.append("\tsource data is in the file: \"").append(source).append("\"\n");
        } else {
            out.append("\tsource data:\n");
            for (String line : lines) {
                out.append('\t').append(line).append('\n');
            }
        }
        return out.toString();
    }

    static void appendReport(String text) throws Exception {
        Path out = Path.of("steps.md");
        String prev = Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8) : "";
        if (!prev.isEmpty() && !prev.endsWith("\n")) {
            prev += "\n";
        }
        if (!prev.isEmpty()) {
            prev += "\n";
        }
        Files.writeString(out, prev + text, StandardCharsets.UTF_8);
    }
}
