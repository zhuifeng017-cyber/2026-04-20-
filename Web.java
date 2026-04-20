import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Web {

    private static final String OUTPUT_FILE = "index.html";
    private static final int SPELL_CHECK_PORT = 8080;

    // -----------------------------------------------------------------------
    // 1. Network helper
    // -----------------------------------------------------------------------
    public String fetchURL(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 WebProject/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("fetchURL error [" + urlString + "]: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // 2. Minimal JSON string extractor (no external libraries)
    // -----------------------------------------------------------------------
    public String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return null;
        start += marker.length();
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char nx = json.charAt(i + 1);
                switch (nx) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(nx);
                }
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // 3. Fetch Wikipedia summary (source 1 & 2 & 3)
    // -----------------------------------------------------------------------
    public Map<String, String> fetchWikipedia(String topic) {
        String url = "https://en.wikipedia.org/api/rest_v1/page/summary/"
                + topic.replace(" ", "_");
        String json = fetchURL(url);
        Map<String, String> data = new HashMap<>();
        if (json != null) {
            data.put("title",     orDefault(extractJsonString(json, "title"), topic));
            data.put("extract",   orDefault(extractJsonString(json, "extract"), "No summary available."));
            data.put("sourceUrl", "https://en.wikipedia.org/wiki/" + topic.replace(" ", "_"));
            // thumbnail is nested; look for "source" inside "thumbnail" block
            int ti = json.indexOf("\"thumbnail\":");
            if (ti != -1) {
                String thumb = json.substring(ti);
                data.put("imageUrl", orDefault(extractJsonString(thumb, "source"), ""));
            }
        } else {
            data.put("title",    topic);
            data.put("extract",  "Could not load content. Please check your internet connection.");
            data.put("sourceUrl","https://en.wikipedia.org/wiki/" + topic.replace(" ", "_"));
        }
        return data;
    }

    // -----------------------------------------------------------------------
    // 4. Fetch trivia from Open Trivia DB (source 3)
    // -----------------------------------------------------------------------
    public List<String> fetchTrivia() {
        String url = "https://opentdb.com/api.php?amount=4&category=18&type=boolean";
        String json = fetchURL(url);
        List<String> list = new ArrayList<>();
        if (json != null) {
            int pos = 0;
            while ((pos = json.indexOf("\"question\":\"", pos)) != -1) {
                pos += "\"question\":\"".length();
                StringBuilder q = new StringBuilder();
                while (pos < json.length()) {
                    char c = json.charAt(pos);
                    if (c == '\\' && pos + 1 < json.length()) {
                        char nx = json.charAt(pos + 1);
                        if (nx == '"') q.append('"');
                        else if (nx == '\'') q.append('\'');
                        else q.append(nx);
                        pos += 2;
                    } else if (c == '"') { break; }
                    else { q.append(c); pos++; }
                }
                String question = q.toString()
                        .replace("&amp;", "&").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&#039;", "'");
                if (!question.isEmpty()) list.add(question);
                pos++;
            }
        }
        if (list.isEmpty()) {
            list.add("The first computer bug was an actual insect found in a relay.");
            list.add("The Internet was originally called ARPANET.");
            list.add("Java was originally designed for interactive television.");
            list.add("The first hard disk could store only 5 MB of data.");
        }
        return list;
    }

    // -----------------------------------------------------------------------
    // 5. Generate random relevant comment based on live content
    // -----------------------------------------------------------------------
    public String generateRandomComment(String content) {
        if (content == null) content = "";
        String[] words = content.toLowerCase().split("[^a-z]+");
        Set<String> stop = new HashSet<>(Arrays.asList(
                "the","a","an","is","are","was","were","be","been","being","have","has","had",
                "do","does","did","will","would","could","should","may","might","shall","can",
                "of","in","on","at","to","for","with","by","from","as","it","its","this","that",
                "these","those","and","or","but","not","also","than","more","which","such","into"));
        List<String> meaningful = new ArrayList<>();
        for (String w : words)
            if (w.length() > 4 && !stop.contains(w)) meaningful.add(w);

        String kw = meaningful.isEmpty() ? "technology"
                : meaningful.get(new Random().nextInt(Math.min(meaningful.size(), 15)));

        String[] templates = {
            "The concept of <b>" + kw + "</b> continues to reshape our understanding of the world.",
            "Recent developments in <b>" + kw + "</b> show remarkable progress in this field.",
            "Experts believe <b>" + kw + "</b> will play a crucial role in future innovations.",
            "The study of <b>" + kw + "</b> opens new possibilities for scientific discovery.",
            "Many researchers are focusing on <b>" + kw + "</b> as the next frontier of exploration.",
            "The implications of <b>" + kw + "</b> extend far beyond what we currently understand.",
            "As we learn more about <b>" + kw + "</b>, new questions continue to emerge.",
            "The field of <b>" + kw + "</b> is evolving faster than ever before."
        };
        return templates[new Random().nextInt(templates.length)];
    }

    // -----------------------------------------------------------------------
    // 6. Start spell-check backend server
    // -----------------------------------------------------------------------
    public HttpServer startSpellCheckServer(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);

        server.createContext("/spellcheck", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String word = "";
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("word=")) {
                        try { word = URLDecoder.decode(param.substring(5), "UTF-8"); }
                        catch (Exception e) { word = param.substring(5); }
                    }
                }
            }

            String response;
            if (word.trim().isEmpty()) {
                response = "{\"error\":\"No word provided\"}";
            } else {
                String dictUrl = "https://api.dictionaryapi.dev/api/v2/entries/en/"
                        + URLEncoder.encode(word.toLowerCase().trim(), "UTF-8");
                String dictResp = fetchURL(dictUrl);

                if (dictResp != null && dictResp.startsWith("[")) {
                    String def = extractJsonString(dictResp, "definition");
                    String pos = extractJsonString(dictResp, "partOfSpeech");
                    if (def == null) def = "Definition not available.";
                    if (pos == null) pos = "unknown";
                    def = def.replace("\\", "\\\\").replace("\"", "\\\"")
                             .replace("\n", " ").replace("\r", "");
                    response = String.format(
                        "{\"valid\":true,\"word\":\"%s\",\"partOfSpeech\":\"%s\",\"definition\":\"%s\"}",
                        escapeJson(word), escapeJson(pos), escapeJson(def));
                } else {
                    response = String.format(
                        "{\"valid\":false,\"word\":\"%s\",\"message\":\"Word not found in dictionary.\"}",
                        escapeJson(word));
                }
            }

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        server.setExecutor(null);
        server.start();
        return server;
    }

    // -----------------------------------------------------------------------
    // 7. Generate the HTML page
    // -----------------------------------------------------------------------
    public void generateHTML(Map<String, String> w1, Map<String, String> w2,
                              Map<String, String> w3, List<String> trivia,
                              String randomComment) throws Exception {
        String html = buildHTML(w1, w2, w3, trivia, randomComment);
        Files.write(Paths.get(OUTPUT_FILE), html.getBytes(StandardCharsets.UTF_8));
        System.out.println("HTML page generated → " + OUTPUT_FILE);
    }

    private String buildHTML(Map<String, String> w1, Map<String, String> w2,
                              Map<String, String> w3, List<String> trivia,
                              String randomComment) {

        String img1 = w1.getOrDefault("imageUrl", "");
        String img2 = w2.getOrDefault("imageUrl", "");
        String img3 = w3.getOrDefault("imageUrl", "");

        // Fallback images (Wikimedia Commons public domain)
        if (img1.isEmpty()) img1 = "https://upload.wikimedia.org/wikipedia/commons/thumb/0/02/Earth_flag_PD.jpg/320px-Earth_flag_PD.jpg";
        if (img2.isEmpty()) img2 = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1e/Stonehenge_Closeup.jpg/320px-Stonehenge_Closeup.jpg";
        if (img3.isEmpty()) img3 = "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9c/Golden_Gate_Bridge_from_Baker_Beach.jpg/320px-Golden_Gate_Bridge_from_Baker_Beach.jpg";

        // A public-domain NASA audio clip (Apollo 11 countdown)
        String soundUrl = "https://upload.wikimedia.org/wikipedia/commons/4/4e/NASA_STS-1_launch_audio.ogg";

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
          .append("<meta charset=\"UTF-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
          .append("<title>Space &amp; Technology – Live News Hub</title>\n")
          .append("<style>\n")
          .append(CSS)
          .append("</style>\n</head>\n<body>\n")
          .append("<header>\n")
          .append("  <h1>&#x1F680; Space &amp; Technology – Live News Hub</h1>\n")
          .append("  <p class=\"subtitle\">Aggregated live content from Wikipedia &amp; Open Trivia DB</p>\n")
          .append("</header>\n\n")

          // Random comment banner
          .append("<section class=\"comment-banner\">\n")
          .append("  <span class=\"label\">AI Comment</span>\n")
          .append("  <p>").append(randomComment).append("</p>\n")
          .append("</section>\n\n")

          // Sound link
          .append("<section class=\"sound-section\">\n")
          .append("  <h2>&#x1F50A; Listen: NASA STS-1 Launch Audio</h2>\n")
          .append("  <p>Click the link below to play the sound:</p>\n")
          .append("  <a class=\"sound-link\" href=\"").append(soundUrl).append("\" target=\"_blank\"\n")
          .append("     onclick=\"playSound(event)\">&#x25B6; Play NASA Launch Sound</a>\n")
          .append("  <audio id=\"bgAudio\" src=\"").append(soundUrl).append("\"></audio>\n")
          .append("</section>\n\n")

          // 3 Wikipedia cards
          .append("<section class=\"articles\">\n")
          .append("  <h2>&#x1F4F0; Live Articles from Wikipedia</h2>\n")
          .append("  <div class=\"cards\">\n")
          .append(buildCard(w1, img1, 1))
          .append(buildCard(w2, img2, 2))
          .append(buildCard(w3, img3, 3))
          .append("  </div>\n</section>\n\n")

          // Trivia
          .append("<section class=\"trivia\">\n")
          .append("  <h2>&#x1F4A1; Tech Trivia (from Open Trivia DB)</h2>\n")
          .append("  <ul>\n");
        for (String t : trivia)
            sb.append("    <li>").append(escapeHtml(t)).append("</li>\n");
        sb.append("  </ul>\n</section>\n\n")

          // Spell checker
          .append("<section class=\"spellcheck\">\n")
          .append("  <h2>&#x1F4DD; Spell Checker (Java Backend)</h2>\n")
          .append("  <p>Type a word and click <b>Check</b> – the Java server queries an online dictionary.</p>\n")
          .append("  <div class=\"spell-input\">\n")
          .append("    <input type=\"text\" id=\"wordInput\" placeholder=\"Enter a word...\" />\n")
          .append("    <button onclick=\"checkSpelling()\">Check</button>\n")
          .append("  </div>\n")
          .append("  <div id=\"spellResult\"></div>\n")
          .append("</section>\n\n")

          .append("<footer>\n")
          .append("  <p>Sources: <a href=\"https://en.wikipedia.org\">Wikipedia</a> | ")
          .append("<a href=\"https://opentdb.com\">Open Trivia DB</a> | ")
          .append("<a href=\"https://dictionaryapi.dev\">Free Dictionary API</a></p>\n")
          .append("  <p>Generated by Web.java &copy; 2026</p>\n")
          .append("</footer>\n\n")
          .append("<script>\n").append(JS).append("</script>\n")
          .append("</body>\n</html>\n");
        return sb.toString();
    }

    private String buildCard(Map<String, String> data, String imgUrl, int idx) {
        String title   = escapeHtml(data.getOrDefault("title", "Unknown"));
        String extract = escapeHtml(data.getOrDefault("extract", ""));
        String srcUrl  = data.getOrDefault("sourceUrl", "#");
        // Truncate extract
        if (extract.length() > 350) extract = extract.substring(0, 347) + "...";
        return "    <div class=\"card\">\n"
             + "      <img src=\"" + imgUrl + "\" alt=\"" + title + "\"\n"
             + "           class=\"zoomable\" id=\"img" + idx + "\"\n"
             + "           onclick=\"toggleZoom(this)\"\n"
             + "           onmouseover=\"zoomIn(this)\" onmouseout=\"zoomOut(this)\" />\n"
             + "      <div class=\"card-body\">\n"
             + "        <h3>" + title + "</h3>\n"
             + "        <p>" + extract + "</p>\n"
             + "        <a href=\"" + srcUrl + "\" target=\"_blank\">Read more on Wikipedia &#x2192;</a>\n"
             + "      </div>\n"
             + "    </div>\n";
    }

    // -----------------------------------------------------------------------
    // 8. Utilities
    // -----------------------------------------------------------------------
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
    }

    private static String orDefault(String val, String def) {
        return (val == null || val.isEmpty()) ? def : val;
    }

    // -----------------------------------------------------------------------
    // 9. CSS
    // -----------------------------------------------------------------------
    private static final String CSS =
        "* { box-sizing: border-box; margin: 0; padding: 0; }\n"
        + "body { font-family: 'Segoe UI', Arial, sans-serif; background: #0d1117; color: #c9d1d9; line-height: 1.6; }\n"
        + "a { color: #58a6ff; text-decoration: none; }\n"
        + "a:hover { text-decoration: underline; }\n"
        + "header { background: linear-gradient(135deg, #161b22, #1f2937);\n"
        + "  padding: 40px 20px; text-align: center; border-bottom: 2px solid #30363d; }\n"
        + "header h1 { font-size: 2.2rem; color: #f0f6fc; }\n"
        + ".subtitle { color: #8b949e; margin-top: 8px; }\n"
        + "section { max-width: 1100px; margin: 30px auto; padding: 0 20px; }\n"
        + "section h2 { font-size: 1.4rem; color: #f0f6fc; margin-bottom: 16px;\n"
        + "  border-left: 4px solid #58a6ff; padding-left: 12px; }\n"
        + ".comment-banner { background: #161b22; border: 1px solid #30363d;\n"
        + "  border-left: 5px solid #3fb950; border-radius: 8px; padding: 18px 24px; }\n"
        + ".comment-banner .label { display: inline-block; background: #3fb950; color: #0d1117;\n"
        + "  font-size: 0.75rem; font-weight: bold; padding: 2px 8px;\n"
        + "  border-radius: 12px; margin-bottom: 8px; text-transform: uppercase; }\n"
        + ".comment-banner p { color: #c9d1d9; font-style: italic; }\n"
        + ".sound-section { background: #161b22; border: 1px solid #30363d;\n"
        + "  border-radius: 8px; padding: 20px 24px; }\n"
        + ".sound-link { display: inline-block; background: #1f6feb; color: #fff;\n"
        + "  padding: 10px 22px; border-radius: 6px; font-weight: bold;\n"
        + "  margin-top: 10px; transition: background 0.2s; }\n"
        + ".sound-link:hover { background: #388bfd; text-decoration: none; }\n"
        + ".cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }\n"
        + ".card { background: #161b22; border: 1px solid #30363d; border-radius: 10px;\n"
        + "  overflow: hidden; transition: box-shadow 0.3s; }\n"
        + ".card:hover { box-shadow: 0 4px 20px rgba(88,166,255,0.2); }\n"
        + ".card img { width: 100%; height: 200px; object-fit: cover;\n"
        + "  cursor: zoom-in; transition: transform 0.35s ease; display: block; }\n"
        + ".card img.zoomed { transform: scale(1.8); cursor: zoom-out; z-index: 10; position: relative; }\n"
        + ".card-body { padding: 16px; }\n"
        + ".card-body h3 { font-size: 1.1rem; color: #f0f6fc; margin-bottom: 8px; }\n"
        + ".card-body p { font-size: 0.9rem; color: #8b949e; margin-bottom: 12px; }\n"
        + ".trivia { background: #161b22; border: 1px solid #30363d;\n"
        + "  border-radius: 8px; padding: 20px 24px; }\n"
        + ".trivia ul { list-style: none; padding: 0; }\n"
        + ".trivia li { padding: 10px 0; border-bottom: 1px solid #21262d;\n"
        + "  color: #c9d1d9; }\n"
        + ".trivia li::before { content: '❓ '; }\n"
        + ".trivia li:last-child { border-bottom: none; }\n"
        + ".spellcheck { background: #161b22; border: 1px solid #30363d;\n"
        + "  border-radius: 8px; padding: 20px 24px; }\n"
        + ".spell-input { display: flex; gap: 10px; margin: 14px 0; }\n"
        + ".spell-input input { flex: 1; padding: 10px 14px; background: #0d1117;\n"
        + "  border: 1px solid #30363d; border-radius: 6px; color: #c9d1d9; font-size: 1rem; }\n"
        + ".spell-input button { padding: 10px 22px; background: #238636;\n"
        + "  color: #fff; border: none; border-radius: 6px; cursor: pointer;\n"
        + "  font-size: 1rem; font-weight: bold; transition: background 0.2s; }\n"
        + ".spell-input button:hover { background: #2ea043; }\n"
        + "#spellResult { margin-top: 12px; padding: 14px; border-radius: 6px;\n"
        + "  font-size: 0.95rem; display: none; }\n"
        + "#spellResult.valid { background: #1a3a22; border: 1px solid #3fb950; color: #3fb950; }\n"
        + "#spellResult.invalid { background: #3a1a1a; border: 1px solid #f85149; color: #f85149; }\n"
        + "#spellResult.error { background: #1e2a3a; border: 1px solid #58a6ff; color: #58a6ff; }\n"
        + "footer { text-align: center; padding: 30px 20px;\n"
        + "  color: #8b949e; font-size: 0.85rem;\n"
        + "  border-top: 1px solid #21262d; margin-top: 40px; }\n"
        + "footer a { color: #58a6ff; }\n";

    // -----------------------------------------------------------------------
    // 10. JavaScript
    // -----------------------------------------------------------------------
    private static final String JS =
        "// Image zoom on hover and click\n"
        + "function zoomIn(img) {\n"
        + "  if (!img.classList.contains('zoomed')) img.style.transform = 'scale(1.5)';\n"
        + "}\n"
        + "function zoomOut(img) {\n"
        + "  if (!img.classList.contains('zoomed')) img.style.transform = 'scale(1.0)';\n"
        + "}\n"
        + "function toggleZoom(img) {\n"
        + "  img.classList.toggle('zoomed');\n"
        + "  img.style.transform = img.classList.contains('zoomed') ? 'scale(1.8)' : 'scale(1.0)';\n"
        + "}\n\n"
        + "// Play sound\n"
        + "function playSound(e) {\n"
        + "  e.preventDefault();\n"
        + "  var audio = document.getElementById('bgAudio');\n"
        + "  if (audio.paused) { audio.play(); }\n"
        + "  else { audio.pause(); audio.currentTime = 0; }\n"
        + "}\n\n"
        + "// Spell check via Java backend\n"
        + "function checkSpelling() {\n"
        + "  var word = document.getElementById('wordInput').value.trim();\n"
        + "  var resultDiv = document.getElementById('spellResult');\n"
        + "  if (!word) { showResult('error', 'Please enter a word first.'); return; }\n"
        + "  resultDiv.style.display = 'block';\n"
        + "  resultDiv.className = 'error';\n"
        + "  resultDiv.textContent = 'Checking...';\n"
        + "  fetch('http://localhost:" + SPELL_CHECK_PORT + "/spellcheck?word=' + encodeURIComponent(word))\n"
        + "    .then(function(r) { return r.json(); })\n"
        + "    .then(function(data) {\n"
        + "      if (data.valid) {\n"
        + "        showResult('valid',\n"
        + "          '✓ \"' + data.word + '\" is spelled correctly!\\n'\n"
        + "          + 'Part of speech: ' + data.partOfSpeech + '\\n'\n"
        + "          + 'Definition: ' + data.definition);\n"
        + "      } else {\n"
        + "        showResult('invalid', '✗ \"' + data.word + '\" — ' + (data.message || 'Not found in dictionary.'));\n"
        + "      }\n"
        + "    })\n"
        + "    .catch(function(err) {\n"
        + "      showResult('error',\n"
        + "        '⚠ Could not reach the Java spell-check server.\\n'\n"
        + "        + 'Make sure Web.java is running (port " + SPELL_CHECK_PORT + ").\\nError: ' + err);\n"
        + "    });\n"
        + "}\n\n"
        + "function showResult(cls, msg) {\n"
        + "  var d = document.getElementById('spellResult');\n"
        + "  d.className = cls;\n"
        + "  d.style.display = 'block';\n"
        + "  d.style.whiteSpace = 'pre-line';\n"
        + "  d.textContent = msg;\n"
        + "}\n\n"
        + "// Allow pressing Enter in the input box\n"
        + "document.addEventListener('DOMContentLoaded', function() {\n"
        + "  document.getElementById('wordInput').addEventListener('keydown', function(e) {\n"
        + "    if (e.key === 'Enter') checkSpelling();\n"
        + "  });\n"
        + "});\n";

    // -----------------------------------------------------------------------
    // 11. Main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        Web web = new Web();

        System.out.println("=== Web Project – Space & Technology ===");
        System.out.println("Fetching live content from the Internet...\n");

        // Source 1: Wikipedia – Space exploration
        System.out.print("[1/3] Wikipedia: Space exploration ... ");
        Map<String, String> space = web.fetchWikipedia("Space_exploration");
        System.out.println(space.get("title") != null ? "OK" : "FAILED (using fallback)");

        // Source 2: Wikipedia – Artificial intelligence
        System.out.print("[2/3] Wikipedia: Artificial intelligence ... ");
        Map<String, String> ai = web.fetchWikipedia("Artificial_intelligence");
        System.out.println(ai.get("title") != null ? "OK" : "FAILED (using fallback)");

        // Source 3: Wikipedia – Climate change
        System.out.print("[3/3] Wikipedia: Climate change ... ");
        Map<String, String> climate = web.fetchWikipedia("Climate_change");
        System.out.println(climate.get("title") != null ? "OK" : "FAILED (using fallback)");

        // Extra source: Open Trivia DB
        System.out.print("[+]   Open Trivia DB (tech facts) ... ");
        List<String> trivia = web.fetchTrivia();
        System.out.println(trivia.size() + " items");

        // Random comment from live content
        String combined = space.getOrDefault("extract", "") + " "
                        + ai.getOrDefault("extract", "") + " "
                        + climate.getOrDefault("extract", "");
        String comment = web.generateRandomComment(combined);

        // Generate HTML
        System.out.println("\nGenerating HTML page...");
        web.generateHTML(space, ai, climate, trivia, comment);

        // Start spell-check backend
        System.out.println("Starting spell-check server on port " + SPELL_CHECK_PORT + " ...");
        HttpServer server = web.startSpellCheckServer(SPELL_CHECK_PORT);
        System.out.println("Server running at http://localhost:" + SPELL_CHECK_PORT + "/spellcheck?word=hello");

        System.out.println("\n=========================================");
        System.out.println("Open  index.html  in your browser.");
        System.out.println("The spell checker requires this program to keep running.");
        System.out.println("Press  Ctrl+C  to stop.\n");

        // Keep JVM alive
        Thread.currentThread().join();
    }
}
