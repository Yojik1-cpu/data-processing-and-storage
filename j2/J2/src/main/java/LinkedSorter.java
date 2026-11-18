import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LinkedSorter {
    static final class Node {
        final String value;
        Node next;

        Node(String value) {
            this.value = value;
        }
    }

    static final class Chain implements Iterable<String> {
        private final Node head = new Node(null);

        void addFirst(String value) {
            synchronized (head) {
                Node node = new Node(value);
                node.next = head.next;
                head.next = node;
            }
        }

        @Override
        public Iterator<String> iterator() {
            List<String> copy = new ArrayList<>();
            synchronized (head) {
                if (head.next != null) {
                    synchronized (head.next) {
                        copyLocked(head.next, copy);
                    }
                }
            }
            return copy.iterator();
        }

        private void copyLocked(Node cur, List<String> copy) {
            copy.add(cur.value);
            Node next = cur.next;
            if (next == null) {
                return;
            }
            synchronized (next) {
                copyLocked(next, copy);
            }
        }
    }

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
        final Chain chain = new Chain();
        final AtomicLong steps = new AtomicLong();
        for (int i = 0; i < threadCount; i++) {
            Thread worker = new Thread(() -> sort(chain, delay, steps));
            worker.setDaemon(true);
            worker.start();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    for (String value : chain) {
                        System.out.println(value);
                    }
                    System.out.println("steps: " + steps.get());
                } else {
                    addLine(chain, line);
                }
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static void addLine(Chain chain, String line) {
        int n = line.length();
        if (n <= 80) {
            chain.addFirst(line);
            return;
        }
        for (int start = ((n - 1) / 80) * 80; start >= 0; start -= 80) {
            int end = Math.min(start + 80, n);
            chain.addFirst(line.substring(start, end));
            if (start == 0) {
                break;
            }
        }
    }

    static void sort(Chain chain, long delay, AtomicLong steps) {
        while (true) {
            Node prev = chain.head;
            boolean stepped = false;
            while (true) {
                Node advance = null;
                boolean did = false;
                synchronized (prev) {
                    Node left = prev.next;
                    if (left != null) {
                        synchronized (left) {
                            Node right = left.next;
                            if (right != null) {
                                synchronized (right) {
                                    if (!pause(delay)) {
                                        return;
                                    }
                                    steps.incrementAndGet();
                                    if (left.value.compareTo(right.value) > 0) {
                                        left.next = right.next;
                                        right.next = left;
                                        prev.next = right;
                                        advance = right;
                                    } else {
                                        advance = left;
                                    }
                                    did = true;
                                }
                            }
                        }
                    }
                }
                if (!did) {
                    break;
                }
                if (!pause(delay)) {
                    return;
                }
                prev = advance;
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
        System.err.println("usage: java LinkedSorter [amount of threads] [delay_ms]");
        System.exit(1);
    }
}
