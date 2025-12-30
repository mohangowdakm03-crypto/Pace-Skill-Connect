import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors; // Added for Multithreading
import java.util.stream.Collectors;

public class PaceBackend {

    private static final int PORT = 8080;
    private static final String DB_FILE = "pace_students_db.txt";
    // 'Vector' is a Thread-Safe version of ArrayList
    private static List<Student> students = new Vector<>();

    public static void main(String[] args) throws IOException {
        loadDataFromFile();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new HtmlHandler());
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/search", new SearchHandler());

        // --- MULTITHREADING ENABLED ---
        // This allows multiple students to connect at the same time without waiting
        server.setExecutor(Executors.newCachedThreadPool()); 
        
        System.out.println("==========================================");
        System.out.println("🚀 PACE JAVA BACKEND RUNNING (MULTITHREADED)");
        System.out.println("👉 GO TO THIS URL: http://localhost:" + PORT);
        System.out.println("==========================================");
        server.start();
    }

    // 1. SERVE HTML
    static class HtmlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File file = new File("index.html");
            if (!file.exists()) {
                String response = "<h1>Error: index.html not found!</h1>";
                exchange.sendResponseHeaders(404, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }
            byte[] response = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    // 2. REGISTER
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(isr);
                    String body = br.lines().collect(Collectors.joining());

                    System.out.println("📥 Received Data: " + body);

                    Map<String, String> data = parseJson(body);
                    String usn = data.getOrDefault("usn", "").trim().toUpperCase();
                    String name = data.getOrDefault("name", "").trim();
                    String email = data.getOrDefault("email", "").trim().toLowerCase();
                    String skills = data.getOrDefault("skills", "").trim();

                    // Validation
                    if (usn.isEmpty() || name.isEmpty() || email.isEmpty()) {
                        sendResponse(exchange, 400, "❌ Error: Missing Fields");
                        return;
                    }

                    if (!email.startsWith("4pa") || !email.endsWith("@pace.edu.in")) {
                        sendResponse(exchange, 400, "❌ Error: Use College Email (4pa...@pace.edu.in)");
                        return;
                    }

                    // --- SYNCHRONIZED BLOCK (CRITICAL SECTION) ---
                    // This ensures only ONE thread checks for duplicates at a time
                    synchronized(students) {
                        boolean usnExists = students.stream().anyMatch(s -> s.usn.equalsIgnoreCase(usn));
                        if (usnExists) {
                            sendResponse(exchange, 400, "⚠️ USN Already Registered!");
                            return;
                        }
                        boolean emailExists = students.stream().anyMatch(s -> s.email.equalsIgnoreCase(email));
                        if (emailExists) {
                            sendResponse(exchange, 400, "⚠️ Email Already Registered!");
                            return;
                        }

                        // Save
                        Student newStudent = new Student(usn, name, email, skills);
                        students.add(newStudent);
                        saveToFile(newStudent); // This method is also synchronized
                    }

                    System.out.println("✅ Saved: " + name);
                    sendResponse(exchange, 200, "✅ Saved Successfully!");

                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(exchange, 500, "❌ Server Error: " + e.getMessage());
                }
            }
        }
    }

    // 3. SEARCH
    static class SearchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String term = (query != null && query.contains("=")) ? query.split("=")[1].toLowerCase() : "";

            // Vector is thread-safe for reading, so no crash here
            List<Student> results = students.stream()
                    .filter(s -> s.skills.toLowerCase().contains(term) || s.name.toLowerCase().contains(term))
                    .collect(Collectors.toList());

            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < results.size(); i++) {
                Student s = results.get(i);
                String safeSkills = s.skills.replace("\"", "'");
                json.append(String.format("{\"name\":\"%s\",\"usn\":\"%s\",\"email\":\"%s\",\"skills\":\"%s\"}",
                        s.name, s.usn, s.email, safeSkills));
                if (i < results.size() - 1) json.append(",");
            }
            json.append("]");

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            sendResponse(exchange, 200, json.toString());
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        String[] parts = json.split("\",\"");
        for (String part : parts) {
            part = part.replace("\"", "");
            String[] entry = part.split(":", 2);
            if (entry.length == 2) {
                map.put(entry[0].trim(), entry[1].trim());
            }
        }
        return map;
    }

    // --- SYNCHRONIZED FILE WRITING ---
    // The 'synchronized' keyword prevents two threads from writing to the file at the exact same instant
    private static synchronized void saveToFile(Student s) {
        try (FileWriter fw = new FileWriter(DB_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(s.usn + "|" + s.name + "|" + s.email + "|" + s.skills);
        } catch (IOException e) {
            System.err.println("Error writing to file: " + e.getMessage());
        }
    }

    private static void loadDataFromFile() {
        File f = new File(DB_FILE);
        if (!f.exists()) return;
        try (Scanner sc = new Scanner(f)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    students.add(new Student(parts[0], parts[1], parts[2], parts[3]));
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    static class Student {
        String usn, name, email, skills;
        public Student(String u, String n, String e, String s) {
            usn = u;
            name = n;
            email = e;
            skills = s;
        }
    }
}