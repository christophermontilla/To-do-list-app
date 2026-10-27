import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;

public class Main {
    private static final int PORT = 8080;
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final String DATA_FILE = "todo-data.properties";
    private static final String STYLE_FILE = "style.css";

    private final List<Task> tasks = new ArrayList<>();
    private final Path dataPath = Path.of(DATA_FILE);
    private final Path stylePath = Path.of(STYLE_FILE);

    public static void main(String[] args) throws IOException {
        Main app = new Main();
        app.loadTasks();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", app::handleRequest);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("To-do app is running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            if ("/style.css".equals(exchange.getRequestURI().getPath())) {
                serveStylesheet(exchange);
                return;
            }

            if (!"/".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }

            boolean darkMode = "dark".equals(readCookie(exchange, "todo-theme"));
            String method = exchange.getRequestMethod();

            if ("POST".equals(method)) {
                Map<String, String> form = parseForm(readRequestBody(exchange));
                String action = form.getOrDefault("action", "");

                if ("theme".equals(action)) {
                    darkMode = !darkMode;
                    exchange.getResponseHeaders().add(
                            "Set-Cookie",
                            "todo-theme=" + (darkMode ? "dark" : "light")
                                    + "; Path=/; SameSite=Strict");
                    redirect(exchange, "/");
                    return;
                }

                if ("add".equals(action) || "edit".equals(action)) {
                    String text = form.getOrDefault("text", "").trim();
                    if (text.isEmpty() || text.length() > 200) {
                        send(exchange, 400, "text/plain; charset=utf-8",
                                "Task text must contain 1 to 200 characters.");
                        return;
                    }
                }
                if (!List.of("add", "toggle", "edit", "delete",
                        "clear-completed").contains(action)) {
                    send(exchange, 400, "text/plain; charset=utf-8",
                            "Unknown task action.");
                    return;
                }

                synchronized (tasks) {
                    updateTasks(action, form);
                    saveTasks();
                }
                redirect(exchange, "/");
                return;
            }

            if (!"GET".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }

            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            synchronized (tasks) {
                send(exchange, 200, "text/html; charset=utf-8",
                        renderPage(darkMode, query.getOrDefault("filter", "all"),
                                query.getOrDefault("search", "")));
            }
        } catch (Exception error) {
            error.printStackTrace(System.err);
            if (exchange.getResponseCode() == -1) {
                send(exchange, 500, "text/plain; charset=utf-8",
                        "The request failed. Check the Java server output for details.");
            }
        } finally {
            exchange.close();
        }
    }

    private void updateTasks(String action, Map<String, String> form) {
        String id = form.getOrDefault("id", "");

        switch (action) {
            case "add":
                String text = form.getOrDefault("text", "").trim();
                if (!text.isEmpty() && text.length() <= 200) {
                    tasks.add(0, new Task(UUID.randomUUID().toString(), text,
                            LocalDate.now().toString()));
                }
                break;
            case "toggle":
                findTask(id).ifPresent(task -> task.completed = !task.completed);
                break;
            case "edit":
                String editedText = form.getOrDefault("text", "").trim();
                if (!editedText.isEmpty() && editedText.length() <= 200) {
                    findTask(id).ifPresent(task -> task.text = editedText);
                }
                break;
            case "delete":
                tasks.removeIf(task -> task.id.equals(id));
                break;
            case "clear-completed":
                tasks.removeIf(task -> task.completed);
                break;
            default:
                break;
        }
    }

    private java.util.Optional<Task> findTask(String id) {
        return tasks.stream().filter(task -> task.id.equals(id)).findFirst();
    }

    private String renderPage(boolean darkMode, String filter, String search) {
        String safeFilter = List.of("all", "active", "completed").contains(filter)
                ? filter : "all";
        String normalizedSearch = search.trim().toLowerCase();
        long completed = tasks.stream().filter(task -> task.completed).count();
        long active = tasks.size() - completed;
        List<Task> visibleTasks = tasks.stream()
                .filter(task -> "all".equals(safeFilter)
                        || ("active".equals(safeFilter) && !task.completed)
                        || ("completed".equals(safeFilter) && task.completed))
                .filter(task -> task.text.toLowerCase().contains(normalizedSearch))
                .collect(java.util.stream.Collectors.toList());

        StringBuilder html = new StringBuilder("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>My To-Do List</title>
                    <link rel="stylesheet" href="/style.css">
                </head>
                <body%CLASS%>
                    <main class="todo-container">
                        <form class="theme-form" method="post">
                            <input type="hidden" name="action" value="theme">
                            <button id="theme-toggle" class="theme-toggle" type="submit"
                                    aria-pressed="%PRESSED%">%THEME_LABEL%</button>
                        </form>
                        <header class="header">
                            <div class="logo">✓</div>
                            <h1>My To-Do List</h1>
                            <p>Organize your day, one task at a time.</p>
                        </header>
                        <form class="todo-form" method="post">
                            <input type="hidden" name="action" value="add">
                            <input type="text" name="text" placeholder="What do you need to do?"
                                   maxlength="200" autocomplete="off" aria-label="New task" required>
                            <button type="submit" id="add-button">+ Add</button>
                        </form>
                        <form class="search-box" method="get">
                            <input type="search" name="search" value="%SEARCH%"
                                   placeholder="Search your tasks..." aria-label="Search tasks">
                            <input type="hidden" name="filter" value="%FILTER%">
                        </form>
                        <section class="stats">
                            <div class="stat-card"><span>%TOTAL%</span><small>Total Tasks</small></div>
                            <div class="stat-card"><span>%ACTIVE%</span><small>Remaining</small></div>
                            <div class="stat-card"><span>%COMPLETED%</span><small>Completed</small></div>
                        </section>
                        <nav class="filters" aria-label="Filter tasks">%FILTER_BUTTONS%</nav>
                        <section class="task-section">
                            <div class="task-heading">
                                <h2>Your Tasks</h2>
                                <form method="post">
                                    <input type="hidden" name="action" value="clear-completed">
                                    <button class="clear-btn" type="submit">Clear completed</button>
                                </form>
                            </div>
                            <ul class="todo-list">%TASKS%</ul>
                            %EMPTY_STATE%
                        </section>
                        <footer><p>Stay focused. Keep making progress.</p></footer>
                    </main>
                </body>
                </html>
                """);

        String filterButtons = filterButton("all", safeFilter)
                + filterButton("active", safeFilter)
                + filterButton("completed", safeFilter);
        StringBuilder taskMarkup = new StringBuilder();
        for (Task task : visibleTasks) {
            taskMarkup.append(renderTask(task));
        }

        String emptyState = visibleTasks.isEmpty()
                ? "<div class=\"empty-state\"><div class=\"empty-icon\">📝</div>"
                + "<h3>" + (tasks.isEmpty() ? "No tasks yet" : "Nothing to show") + "</h3>"
                + "<p>" + (tasks.isEmpty() ? "Add your first task to get started!"
                : "Try another search or change your filter.") + "</p></div>"
                : "";

        return html.toString()
                .replace("%CLASS%", darkMode ? " class=\"dark-mode\"" : "")
                .replace("%PRESSED%", Boolean.toString(darkMode))
                .replace("%THEME_LABEL%", darkMode ? "☀ Light mode" : "☾ Dark mode")
                .replace("%SEARCH%", escapeHtml(search))
                .replace("%FILTER%", escapeHtml(safeFilter))
                .replace("%TOTAL%", Integer.toString(tasks.size()))
                .replace("%ACTIVE%", Long.toString(active))
                .replace("%COMPLETED%", Long.toString(completed))
                .replace("%FILTER_BUTTONS%", filterButtons)
                .replace("%EMPTY_STATE%", emptyState)
                .replace("%TASKS%", taskMarkup);
    }

    private String filterButton(String filter, String currentFilter) {
        String label = Character.toUpperCase(filter.charAt(0)) + filter.substring(1);
        return "<a class=\"filter-btn" + (filter.equals(currentFilter) ? " active" : "")
                + "\" href=\"/?filter=" + filter + "\">" + label + "</a>";
    }

    private String renderTask(Task task) {
        String id = escapeHtml(task.id);
        String text = escapeHtml(task.text);
        String completedClass = task.completed ? " completed" : "";
        String toggleLabel = task.completed ? "Undo" : "Complete";
        return "<li class=\"todo-item" + completedClass + "\">"
                + "<form class=\"task-toggle\" method=\"post\">"
                + "<input type=\"hidden\" name=\"action\" value=\"toggle\">"
                + "<input type=\"hidden\" name=\"id\" value=\"" + id + "\">"
                + "<button class=\"toggle-btn\" type=\"submit\">" + toggleLabel + "</button></form>"
                + "<div class=\"task-content\"><span class=\"task-text\">" + text + "</span>"
                + "<small class=\"task-date\">Added " + escapeHtml(task.createdAt) + "</small></div>"
                + "<details class=\"edit-controls\"><summary>Edit</summary>"
                + "<form class=\"edit-form\" method=\"post\">"
                + "<input type=\"hidden\" name=\"action\" value=\"edit\">"
                + "<input type=\"hidden\" name=\"id\" value=\"" + id + "\">"
                + "<input type=\"text\" name=\"text\" value=\"" + text
                + "\" maxlength=\"200\" aria-label=\"Edit task\" required>"
                + "<button type=\"submit\">Save</button></form></details>"
                + "<form class=\"delete-form\" method=\"post\">"
                + "<input type=\"hidden\" name=\"action\" value=\"delete\">"
                + "<input type=\"hidden\" name=\"id\" value=\"" + id + "\">"
                + "<button class=\"action-btn delete-btn\" type=\"submit\">Delete</button></form>"
                + "</li>";
    }

    private void serveStylesheet(HttpExchange exchange) throws IOException {
        if (!Files.exists(stylePath)) {
            send(exchange, 404, "text/plain; charset=utf-8",
                    "style.css was not found. Run the server from the app folder.");
            return;
        }
        send(exchange, 200, "text/css; charset=utf-8",
                Files.readString(stylePath, StandardCharsets.UTF_8));
    }

    private void loadTasks() throws IOException {
        if (!Files.exists(dataPath)) {
            return;
        }

        Properties data = new Properties();
        try (InputStream input = Files.newInputStream(dataPath)) {
            data.load(input);
        }

        int count = Integer.parseInt(data.getProperty("count", "0"));
        for (int i = 0; i < count; i++) {
            String prefix = "task." + i + ".";
            String id = data.getProperty(prefix + "id");
            String text = data.getProperty(prefix + "text");
            if (id != null && text != null) {
                Task task = new Task(id, text,
                        data.getProperty(prefix + "createdAt", LocalDate.now().toString()));
                task.completed = Boolean.parseBoolean(
                        data.getProperty(prefix + "completed", "false"));
                tasks.add(task);
            }
        }
    }

    private void saveTasks() throws IOException {
        Properties data = new Properties();
        data.setProperty("count", Integer.toString(tasks.size()));
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            String prefix = "task." + i + ".";
            data.setProperty(prefix + "id", task.id);
            data.setProperty(prefix + "text", task.text);
            data.setProperty(prefix + "completed", Boolean.toString(task.completed));
            data.setProperty(prefix + "createdAt", task.createdAt);
        }

        Path temporary = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(temporary)) {
            data.store(output, "To-do list data");
        }
        try {
            Files.move(temporary, dataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, dataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new IOException("Request body is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Map<String, String> parseForm(byte[] body) {
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static String readCookie(HttpExchange exchange, String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return "";
        }
        for (String cookieHeader : cookies) {
            for (String cookie : cookieHeader.split(";")) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && name.equals(parts[0])) {
                    return parts[1];
                }
            }
        }
        return "";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void send(HttpExchange exchange, int status, String contentType,
                             String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class Task {
        private final String id;
        private final String createdAt;
        private String text;
        private boolean completed;

        private Task(String id, String text, String createdAt) {
            this.id = id;
            this.text = text;
            this.createdAt = createdAt;
        }
    }
}
