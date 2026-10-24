package de.schematiclive;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Winziger lokaler Server (nur 127.0.0.1, nur java.base) für den Schematic-Loader im Browser.
 * Der Browser verbindet sich per Server-Sent-Events und muss den zufälligen Code kennen.
 */
public final class LiveServer {
    public static final int PORT = 47615;
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final Path file;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(daemon("schematic-live-io"));
    private final ExecutorService conn = Executors.newCachedThreadPool(daemon("schematic-live-conn"));
    private final Object failLock = new Object();
    private volatile String code;
    private volatile String last = "{\"t\":\"out\"}";
    private volatile boolean running;
    private ServerSocket server;

    public LiveServer(Path file) {
        this.file = file;
        this.code = load();
    }

    /** Code zum Anzeigen, z. B. K7M-2QX. */
    public String code() {
        String c = code;
        return c.substring(0, 3) + "-" + c.substring(3);
    }

    public int port() {
        return PORT;
    }

    public void newCode() {
        String c = random();
        save(c);
        code = c;
        for (Socket s : clients) {
            try { s.close(); } catch (IOException ignored) { }
        }
        clients.clear();
    }

    public void start() {
        try {
            server = new ServerSocket(PORT, 16, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            System.err.println("[Schematic Live] Port " + PORT + " ist belegt: " + e.getMessage());
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    conn.execute(() -> handle(s));
                } catch (IOException e) {
                    if (running) System.err.println("[Schematic Live] " + e.getMessage());
                }
            }
        }, "schematic-live-accept");
        t.setDaemon(true);
        t.start();
        io.scheduleAtFixedRate(() -> write(": ping\n\n"), 5, 5, TimeUnit.SECONDS);
    }

    public void publishPos(double x, double y, double z, float yaw, float pitch, String dim) {
        push(String.format(Locale.ROOT,
                "{\"t\":\"pos\",\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"yaw\":%.1f,\"pitch\":%.1f,\"dim\":\"%s\"}",
                x, y, z, yaw, pitch, dim.replaceAll("[^a-z0-9_:./-]", "")));
    }

    public void publishOut() {
        push("{\"t\":\"out\"}");
    }

    private void push(String json) {
        if (json.equals(last)) return;
        last = json;
        if (!clients.isEmpty()) io.execute(() -> write("data: " + json + "\n\n"));
    }

    private void write(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        for (Socket c : clients) {
            try {
                OutputStream o = c.getOutputStream();
                o.write(b);
                o.flush();
            } catch (IOException e) {
                clients.remove(c);
                try { c.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void handle(Socket s) {
        boolean keep = false;
        try {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null) return;
            for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) { /* Header ignorieren */ }
            String[] p = line.split(" ");
            if (p.length < 2) return;
            String path = p[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }
            if (p[0].equals("OPTIONS")) {
                send(s, "204 No Content", "text/plain", "");
                return;
            }
            boolean ok = codeOk(query);
            boolean api = path.equals("/check") || path.equals("/live");
            if (api && !ok) {
                synchronized (failLock) { // Raten bremsen: höchstens ~4 Versuche pro Sekunde
                    try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                send(s, "403 Forbidden", "application/json", "{\"ok\":false}");
                return;
            }
            if (path.equals("/check")) {
                send(s, "200 OK", "application/json", "{\"ok\":true}");
            } else if (path.equals("/live")) {
                s.setSoTimeout(0);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                        + "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n"
                        + "retry: 2000\n\ndata: " + last + "\n\n";
                OutputStream o = s.getOutputStream();
                o.write(head.getBytes(StandardCharsets.UTF_8));
                o.flush();
                clients.add(s);
                keep = true;
            } else {
                send(s, "404 Not Found", "text/plain", "not found");
            }
        } catch (IOException ignored) {
            // Verbindung weg: egal
        } finally {
            if (!keep) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static void send(Socket s, String status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\n"
                + "Access-Control-Allow-Private-Network: true\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.flush();
    }

    private boolean codeOk(String query) {
        for (String kv : query.split("&")) {
            if (kv.startsWith("code=")) {
                return MessageDigest.isEqual(norm(kv.substring(5)).getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    private static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    private static String random() {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return b.toString();
    }

    private String load() {
        try {
            if (Files.exists(file)) {
                String c = norm(Files.readString(file));
                if (c.length() == 6) return c;
            }
        } catch (IOException ignored) { }
        String c = random();
        save(c);
        return c;
    }

    private void save(String c) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, c + System.lineSeparator());
        } catch (IOException e) {
            System.err.println("[Schematic Live] Code konnte nicht gespeichert werden: " + e.getMessage());
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
