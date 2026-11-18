import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ArraySorter {
    public static void main(String[] args) {
        if (args.length > 2) {
            usage();
        }
        int threadCount = 2;
        int delayMs = 1000;
        try {
            if (args.length >= 1) {
                threadCount = Integer.parseInt(args[0]);
            }
            if (args.length == 2) {
                delayMs = Integer.parseInt(args[1]);
            }
        } catch (NumberFormatException e) {
            usage();
        }
        if (threadCount < 1 || delayMs < 0) {
            usage();
        }
        final long delay = delayMs;
        final List<String> list = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong steps = new AtomicLong();
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(() -> sort(list, delay, steps));
            worker.setDaemon(true);
            worker.start();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    List<String> copy = new ArrayList<>();
                    synchronized (list) {
                        for (String value : list) {
                            copy.add(value);
                        }
                    }
                    for (String value : copy) {
                        System.out.println(value);
                    }
                    System.out.println("steps: " + steps.get());
                } else {
                    addLine(list, line);
                }
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static void addLine(List<String> list, String line) {
        int n = line.length();
        if (n <= 80) {
            list.add(0, line);
            return;
        }
        for (int start = ((n - 1) / 80) * 80; start >= 0; start -= 80) {
            int end = Math.min(start + 80, n);
            list.add(0, line.substring(start, end));
            if (start == 0) {
                break;
            }
        }
    }

    static void sort(List<String> list, long delay, AtomicLong steps) {
        while (true) {
            int i = 0;
            boolean stepped = false;
            while (true) {
                boolean did = false;
                synchronized (list) {
                    if (i < list.size() - 1) {
                        if (!pause(delay)) {
                            return;
                        }
                        steps.incrementAndGet();
                        String left = list.get(i);
                        String right = list.get(i + 1);
                        if (left.compareTo(right) > 0) {
                            list.set(i, right);
                            list.set(i + 1, left);
                        }
                        did = true;
                    }
                }
                if (!did) {
                    break;
                }
                if (!pause(delay)) {
                    return;
                }
                i++;
                stepped = true;
            }
            if (!stepped && !pause(delay)) {
                return;
            }
        }
    }

    static boolean pause(long delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    static void usage() {
        System.err.println("usage: java ArraySorter [amount of threads] [delay_ms]");
        System.exit(1);
    }
}
