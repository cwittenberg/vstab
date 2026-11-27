package app.vstab;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Canonical app-owned OpenSSH config. This class never executes ssh or reads keys. */
final class SshConfigStore {
    static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_FILES = 32, MAX_DEPTH = 8, MAX_HOSTS = 100;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "hostname", "user", "port", "identityfile", "identitiesonly", "preferredauthentications"));
    private final File home, ssh, config;
    private final Object monitor;

    static final class Snapshot {
        final String content, revision;
        final long mtime;
        final int size;
        final List<Diagnostic> diagnostics;
        Snapshot(String content, long mtime, List<Diagnostic> diagnostics) { this.content = content; this.revision = digest(content.getBytes(StandardCharsets.UTF_8)); this.mtime = mtime; this.size = content.getBytes(StandardCharsets.UTF_8).length; this.diagnostics = diagnostics; }
        JSONObject json() throws IOException {
            try { return new JSONObject().put("content", content).put("revision", revision).put("mtime", mtime).put("size", size).put("diagnostics", diagnosticJson(diagnostics)); }
            catch (Exception error) { throw new IOException("Cannot encode SSH configuration.", error); }
        }
    }

    static final class Diagnostic {
        final int line;
        final String message, severity;
        Diagnostic(int line, String message, String severity) { this.line = Math.max(0, line); this.message = message; this.severity = severity; }
    }

    static final class ConflictException extends IOException {
        ConflictException() { super("SSH_CONFIG_CONFLICT: SSH configuration changed since it was opened. Reload it before saving; your edit has not overwritten the newer file."); }
    }

    static final class Host {
        final String id, alias, hostname, host, user, preferredAuthentications, identityFile;
        final int port;
        final int configLine;
        final List<String> identityPaths, diagnostics;
        final boolean identitiesOnly, connectable, editable;
        Host(String alias, String hostname, int port, String user, List<String> identities,
             boolean identitiesOnly, String auth, List<String> diagnostics, boolean editable, int configLine) {
            this.id = idForAlias(alias); this.alias = alias; this.hostname = hostname; this.host = hostname;
            this.port = port; this.user = user; this.identityPaths = Collections.unmodifiableList(new ArrayList<>(identities));
            this.identityFile = identities.isEmpty() ? "" : identities.get(0);
            this.identitiesOnly = identitiesOnly; this.preferredAuthentications = auth;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(diagnostics)));
            this.connectable = this.diagnostics.isEmpty(); this.editable = editable;
            this.configLine = configLine;
        }
        JSONObject publicMetadata() throws IOException {
            try { return new JSONObject().put("id", id).put("alias", alias).put("label", alias)
                    .put("hostname", hostname).put("host", hostname).put("port", port).put("user", user)
                    .put("identityPaths", new JSONArray(identityPaths)).put("identitiesOnly", identitiesOnly)
                    .put("preferredAuthentications", preferredAuthentications).put("diagnostics", diagnosticJson(toDiagnostics(diagnostics)))
                    .put("configLine", configLine).put("hasIdentity", !identityPaths.isEmpty())
                    .put("connectable", connectable).put("editable", editable); }
            catch (Exception error) { throw new IOException("Cannot encode SSH host metadata.", error); }
        }
    }

    private static final class Line {
        final String raw, key, argument, comment;
        final String parseError;
        final List<String> values;
        final File source;
        final int number, start, end;
        Line(String raw, File source, int number, int start, int end) throws IOException {
            this.raw = raw; this.source = source; this.number = number; this.start = start; this.end = end;
            String body = raw.replaceFirst("[\\r\\n]+$", "");
            int at = 0; while (at < body.length() && Character.isWhitespace(body.charAt(at))) at++;
            int keyStart = at;
            while (at < body.length() && !Character.isWhitespace(body.charAt(at)) && body.charAt(at) != '=' && body.charAt(at) != '#') at++;
            key = body.substring(keyStart, at).toLowerCase(Locale.ROOT);
            while (at < body.length() && Character.isWhitespace(body.charAt(at))) at++;
            if (at < body.length() && body.charAt(at) == '=') { at++; while (at < body.length() && Character.isWhitespace(body.charAt(at))) at++; }
            argument = body.substring(at);
            List<String> parsed; String failure = null;
            try { parsed = key.isEmpty() ? new ArrayList<>() : tokenize(argument); }
            catch (IOException error) { parsed = new ArrayList<>(); failure = error.getMessage(); }
            values = parsed; parseError = failure;
            int commentAt = commentOffset(body);
            comment = commentAt < 0 ? "" : body.substring(commentAt);
        }
        String location() { return source.getName() + ":" + number; }
    }

    SshConfigStore(Context context) throws IOException { this(new File(context.getFilesDir(), "home")); }
    SshConfigStore(File nativeHome) throws IOException {
        home = nativeHome.getCanonicalFile();
        ssh = new File(home, ".ssh").getCanonicalFile(); config = new File(ssh, "config");
        if (!ssh.toPath().startsWith(home.toPath())) throw new IOException("The SSH directory must stay inside the Android home.");
        Files.createDirectories(ssh.toPath()); privatePermissions(ssh, true);
        monitor = LOCKS.computeIfAbsent(config.getAbsolutePath(), ignored -> new Object());
    }

    File configFile() { return config; }

    Snapshot readConfig() throws IOException { synchronized (monitor) { return readUnlocked(); } }
    private Snapshot readUnlocked() throws IOException {
        checkConfigLocation();
        if (!config.exists()) return new Snapshot("", 0, new ArrayList<>());
        String content = readUtf8(config, MAX_BYTES);
        return new Snapshot(content, config.lastModified(), diagnosticsFor(content));
    }

    Snapshot writeConfig(String content, String expectedRevision) throws IOException {
        if (content == null || content.indexOf('\0') >= 0) throw new IOException("SSH configuration must be text without NUL characters.");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("SSH configuration is limited to 256 KiB.");
        if (expectedRevision == null || expectedRevision.isEmpty()) throw new IOException("Read SSH configuration before saving it; a revision is required.");
        synchronized (monitor) {
            checkConfigLocation();
            Path lockPath = new File(ssh, ".vstab-config.lock").toPath();
            if (Files.isSymbolicLink(lockPath)) throw new IOException("The SSH configuration lock must not be a symbolic link.");
            try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                privatePermissions(lockPath.toFile(), false);
                if (!readUnlocked().revision.equals(expectedRevision)) throw conflict();
                Path stage = Files.createTempFile(ssh.toPath(), ".vstab-config-", ".tmp");
                try {
                    privatePermissions(stage.toFile(), false);
                    try (FileChannel output = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                    }
                    if (!readUnlocked().revision.equals(expectedRevision)) throw conflict();
                    Files.move(stage, config.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(stage); }
                return readUnlocked();
            }
        }
    }

    List<Host> list() throws IOException { return profiles(); }
    List<Host> profiles() throws IOException {
        synchronized (monitor) {
            List<Line> lines = expandedLines(readUnlocked().content);
            LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
            for (Line line : lines) if (line.key.equals("host")) for (String value : line.values)
                if (literalAlias(value)) aliases.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            if (aliases.size() > MAX_HOSTS) throw new IOException("SSH configuration has more than 100 explicit hosts.");
            List<Host> result = new ArrayList<>();
            for (String alias : aliases.values()) result.add(resolve(alias, lines));
            return result;
        }
    }

    Host find(String idOrAlias) throws IOException {
        if (idOrAlias == null) return null;
        for (Host host : profiles()) if (host.id.equals(idOrAlias) || host.alias.equalsIgnoreCase(idOrAlias)) return host;
        return null;
    }

    private Host resolve(String alias, List<Line> lines) {
        Map<String, String> options = new LinkedHashMap<>(); List<String> identities = new ArrayList<>(), problems = new ArrayList<>();
        boolean active = true, editable = true; int configLine = 0; boolean located = false;
        for (Line line : lines) {
            if (line.parseError != null) { if (active || line.key.equals("host")) problems.add(line.location() + ": " + line.parseError); if (line.key.equals("host")) active = false; continue; }
            if (line.key.isEmpty() || line.key.equals("include")) continue;
            if (line.key.equals("host")) { active = matches(alias, line.values); if (line.values.stream().anyMatch(v -> v.equalsIgnoreCase(alias))) { if (!line.source.equals(config)) editable = false; if (!located) { configLine = line.number - 1; located = true; } } continue; }
            if (line.key.equals("match")) { problems.add(line.location() + ": Match rules are unsupported. Use Host rules for this connection."); active = false; continue; }
            if (!active) continue;
            if (line.key.equals("vstabincludeerror")) { problems.add(line.values.isEmpty() ? line.argument : line.values.get(0)); continue; }
            if (!FIELDS.contains(line.key)) { problems.add(line.location() + ": " + line.key + " is not supported by vstab. Edit SSH configuration before connecting."); continue; }
            if (line.values.size() != 1) { problems.add(line.location() + ": " + line.key + " needs one value (quote paths containing spaces)."); continue; }
            if (line.key.equals("identityfile")) { if (!line.values.get(0).equalsIgnoreCase("none")) identities.add(line.values.get(0)); }
            else options.putIfAbsent(line.key, line.values.get(0));
        }
        String user = options.getOrDefault("user", System.getProperty("user.name", ""));
        int port = 22;
        try { port = Integer.parseInt(options.getOrDefault("port", "22")); if (port < 1 || port > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException error) { problems.add("Port must be between 1 and 65535."); port = 22; }
        String hostname = expand(options.getOrDefault("hostname", alias), alias, alias, user, port, problems, false);
        List<String> expandedIdentities = new ArrayList<>();
        for (String identity : identities) {
            String path = expand(identity, hostname, alias, user, port, problems, true);
            if (!expandedIdentities.contains(path)) expandedIdentities.add(path);
        }
        String only = options.getOrDefault("identitiesonly", "no");
        if (!only.equalsIgnoreCase("yes") && !only.equalsIgnoreCase("no")) problems.add("IdentitiesOnly must be yes or no.");
        String auth = options.getOrDefault("preferredauthentications", "publickey,password");
        for (String method : auth.split(",", -1)) if (!Arrays.asList("publickey", "password").contains(method))
            problems.add("PreferredAuthentications contains unsupported method: " + method);
        if (!validEndpoint(hostname) || user.isEmpty() || user.matches(".*[\\s/@\\\\].*")) problems.add("Enter a valid HostName and User before connecting.");
        return new Host(alias, hostname, port, user, expandedIdentities, only.equalsIgnoreCase("yes"), auth, problems, editable, configLine);
    }

    private String expand(String value, String hostname, String alias, String user, int port, List<String> problems, boolean identity) {
        StringBuilder out = new StringBuilder();
        for (int at = 0; at < value.length(); at++) {
            char c = value.charAt(at);
            if (c != '%') { out.append(c); continue; }
            if (++at == value.length()) { problems.add("An SSH value ends with an incomplete % token."); out.append('%'); break; }
            char token = value.charAt(at);
            switch (token) {
                case '%': out.append('%'); break;
                case 'h': out.append(hostname); break;
                case 'n': out.append(alias); break;
                case 'r': out.append(user); break;
                case 'p': out.append(port); break;
                case 'd': out.append(home.getAbsolutePath()); break;
                default: problems.add("Unsupported SSH expansion token: %" + token); out.append('%').append(token);
            }
        }
        String result = out.toString();
        if (result.contains("${")) problems.add("Environment-variable expansion is unsupported; use an explicit app-private path.");
        if (identity) {
            if (result.equals("~")) result = home.getAbsolutePath();
            else if (result.startsWith("~/")) result = new File(home, result.substring(2)).getAbsolutePath();
            else if (result.startsWith("~")) problems.add("Only ~ and ~/ paths in the Android home are supported.");
            else if (!new File(result).isAbsolute()) result = new File(home, result).getAbsolutePath();
        }
        return result;
    }

    Host addFromCommand(String command) throws IOException { return addFromCommand(command, null); }
    Host addFromCommand(String command, String requestedAlias) throws IOException {
        if (command == null || command.length() > 8192 || command.indexOf('\n') >= 0 || command.indexOf('\0') >= 0) throw new IOException("Enter one ssh command without a remote command.");
        List<String> words = tokenize(command); if (words.size() < 2 || !words.get(0).equals("ssh")) throw new IOException("Start with ssh, for example: ssh -p 2222 developer@example.org");
        Map<String, String> options = new LinkedHashMap<>(); List<String> identities = new ArrayList<>(); String target = null;
        for (int at = 1; at < words.size(); at++) {
            String word = words.get(at);
            if (word.equals("--")) { if (target != null || ++at != words.size() - 1) throw new IOException("Provide one SSH host after --; remote commands are not saved."); target = words.get(at); break; }
            if (!word.startsWith("-")) { if (target != null) throw new IOException("Remote commands are not saved. Enter only SSH connection options and user@host."); target = word; continue; }
            if (word.length() < 2 || "plio".indexOf(word.charAt(1)) < 0) throw new IOException("Unsupported ssh option: " + word + ". Supported options are -p, -l, -i and -o.");
            char flag = word.charAt(1); String value = word.substring(2);
            if (value.isEmpty()) { if (++at >= words.size()) throw new IOException("Missing value for -" + flag); value = words.get(at); }
            String key = flag == 'p' ? "port" : flag == 'l' ? "user" : flag == 'i' ? "identityfile" : "";
            if (flag == 'o') {
                Line option = new Line(value, config, 1, 0, value.length()); key = option.key;
                if (!FIELDS.contains(key) || option.values.size() != 1) throw new IOException("Unsupported -o setting. Use HostName, User, Port, IdentityFile, IdentitiesOnly or PreferredAuthentications.");
                value = option.values.get(0);
            }
            if (key.equals("identityfile")) identities.add(value); else options.putIfAbsent(key, value);
        }
        if (target == null) throw new IOException("Provide an SSH host.");
        int userAt = target.lastIndexOf('@'); if (userAt >= 0) { options.putIfAbsent("user", target.substring(0, userAt)); target = target.substring(userAt + 1); }
        if (target.startsWith("[") && target.endsWith("]")) target = target.substring(1, target.length() - 1);
        if (!validEndpoint(target)) throw new IOException("Enter a hostname, IPv4 address or IPv6 address without a remote command.");
        options.putIfAbsent("hostname", target);
        synchronized (monitor) {
            Snapshot before = readUnlocked(); String alias = requestedAlias == null || requestedAlias.trim().isEmpty() ? uniqueAlias(target) : requestedAlias.trim();
            if (!literalAlias(alias)) throw new IOException("Use a short SSH alias containing letters, numbers, dots, colons, hyphens or underscores.");
            if (find(alias) != null) throw new IOException("That SSH alias already exists.");
            StringBuilder block = new StringBuilder("Host ").append(quote(alias)).append('\n');
            for (Map.Entry<String, String> option : options.entrySet()) block.append("    ").append(displayKey(option.getKey())).append(' ').append(quote(option.getValue())).append('\n');
            for (String identity : identities) block.append("    IdentityFile ").append(quote(identity)).append('\n');
            String updated = appendBlock(before.content, block.toString());
            // Validate only this new profile before committing; existing unsupported rules remain visible.
            Host parsed = resolve(alias, expandedLines(updated));
            if (parsed.diagnostics.stream().anyMatch(x -> x.startsWith("Port must") || x.startsWith("Enter a valid") || x.startsWith("IdentitiesOnly") || x.startsWith("PreferredAuthentications"))) throw new IOException(String.join(" ", parsed.diagnostics));
            writeConfig(updated, before.revision); return find(alias);
        }
    }

    Host saveHost(String id, String alias, String hostname, int port, String user, List<String> identities,
                  boolean identitiesOnly, String preferredAuthentications) throws IOException {
        if (!literalAlias(alias) || !validEndpoint(hostname) || port < 1 || port > 65535 || user == null || user.isEmpty() || user.matches(".*[\\s/@\\\\].*")) throw new IOException("Enter a valid SSH alias, hostname, account and port.");
        if (identities == null) identities = Collections.emptyList();
        StringBuilder block = new StringBuilder("Host ").append(quote(alias)).append('\n')
                .append("    HostName ").append(quote(hostname)).append('\n').append("    User ").append(quote(user)).append('\n').append("    Port ").append(port).append('\n');
        for (String identity : identities) block.append("    IdentityFile ").append(quote(identity)).append('\n');
        block.append("    IdentitiesOnly ").append(identitiesOnly ? "yes" : "no").append('\n');
        if (preferredAuthentications != null && !preferredAuthentications.isEmpty()) block.append("    PreferredAuthentications ").append(quote(preferredAuthentications)).append('\n');
        synchronized (monitor) {
            Snapshot before = readUnlocked(); Host old = id == null || id.isEmpty() ? null : find(id);
            if (old == null && id != null && !id.isEmpty()) throw new IOException("The configured SSH host no longer exists. Refresh Remote Explorer.");
            Host duplicate = find(alias);
            if (duplicate != null && (old == null || !duplicate.id.equals(old.id))) throw new IOException("That SSH alias already exists.");
            String next;
            if (old == null) next = appendBlock(before.content, block.toString());
            else {
                if (!old.editable) throw new IOException("This host is defined in an Include file. Edit its source file instead.");
                List<Line> lines = parseLines(before.content, config); List<Integer> starts = explicitBlocks(lines, old.alias);
                if (starts.size() != 1 || lines.get(starts.get(0)).values.size() != 1) throw new IOException("This alias uses multiple or shared Host blocks. Edit SSH configuration to preserve its shared rules.");
                int start = starts.get(0), end = blockEnd(lines, start), insertion = lines.get(start).start;
                StringBuilder retained = new StringBuilder();
                for (int at = start; at < end; at++) {
                    Line line = lines.get(at);
                    if (line.key.equals("host") || FIELDS.contains(line.key)) { if (!line.comment.isEmpty()) retained.append(line.comment).append(newline(line.raw)); }
                    else retained.append(line.raw);
                }
                int endOffset = end < lines.size() ? lines.get(end).start : before.content.length();
                next = before.content.substring(0, insertion) + block + retained + before.content.substring(endOffset);
            }
            writeConfig(next, before.revision); return find(alias);
        }
    }

    boolean remove(String id) throws IOException { return removeHost(id); }
    boolean removeHost(String id) throws IOException {
        synchronized (monitor) {
            Host host = find(id); if (host == null) return false;
            if (!host.editable) throw new IOException("This host is defined in an Include file. Edit its source file instead.");
            Snapshot before = readUnlocked(); List<Line> lines = parseLines(before.content, config); StringBuilder next = new StringBuilder();
            for (int at = 0; at < lines.size();) {
                Line line = lines.get(at);
                if (!line.key.equals("host") || line.values.stream().noneMatch(v -> v.equalsIgnoreCase(host.alias))) { next.append(line.raw); at++; continue; }
                List<String> remaining = new ArrayList<>(); for (String value : line.values) if (!value.equalsIgnoreCase(host.alias)) remaining.add(value);
                if (!remaining.isEmpty()) { next.append("Host"); for (String value : remaining) next.append(' ').append(quote(value)); if (!line.comment.isEmpty()) next.append(' ').append(line.comment); next.append(newline(line.raw)); at++; continue; }
                int end = blockEnd(lines, at);
                for (; at < end; at++) { Line removed = lines.get(at); if (removed.key.isEmpty()) next.append(removed.raw); else if (!removed.comment.isEmpty()) next.append(removed.comment).append(newline(removed.raw)); }
            }
            writeConfig(next.toString(), before.revision); return true;
        }
    }

    private List<Line> expandedLines(String content) throws IOException {
        int[] budget = {0, 0}; return expandIncludes(content, config, 0, new HashSet<>(), budget);
    }
    private List<Diagnostic> diagnosticsFor(String content) {
        List<String> problems = new ArrayList<>();
        try {
            for (Line line : expandedLines(content)) {
                if (line.parseError != null) problems.add(line.location() + ": " + line.parseError);
                else if (line.key.equals("vstabincludeerror")) problems.add(line.values.isEmpty() ? line.argument : line.values.get(0));
                else if (line.key.equals("match")) problems.add(line.location() + ": Match rules are unsupported. Use Host rules for this connection.");
                else if (!line.key.isEmpty() && !line.key.equals("host") && !line.key.equals("include") && !FIELDS.contains(line.key)) problems.add(line.location() + ": " + line.key + " is unsupported; edit this setting before connecting.");
                else if (line.key.equals("host") && line.values.isEmpty()) problems.add(line.location() + ": Host needs at least one alias or pattern.");
                else if (FIELDS.contains(line.key) && line.values.size() != 1) problems.add(line.location() + ": " + line.key + " needs one value.");
                else if (line.key.equals("port") && !line.values.isEmpty()) { try { int port = Integer.parseInt(line.values.get(0)); if (port < 1 || port > 65535) throw new NumberFormatException(); } catch (NumberFormatException error) { problems.add(line.location() + ": Port must be between 1 and 65535."); } }
                else if (line.key.equals("identitiesonly") && !line.values.isEmpty() && !line.values.get(0).equalsIgnoreCase("yes") && !line.values.get(0).equalsIgnoreCase("no")) problems.add(line.location() + ": IdentitiesOnly must be yes or no.");
                else if (line.key.equals("preferredauthentications") && !line.values.isEmpty()) for (String method : line.values.get(0).split(",", -1)) if (!Arrays.asList("publickey", "password").contains(method)) problems.add(line.location() + ": Unsupported authentication method: " + method + ". vstab currently supports password and publickey.");
                if ((line.key.equals("identityfile") || line.key.equals("hostname")) && line.values.size() == 1) { List<String> expansions = new ArrayList<>(); expand(line.values.get(0), "host", "alias", "user", 22, expansions, line.key.equals("identityfile")); for (String expansion : expansions) problems.add(line.location() + ": " + expansion); }
            }
        } catch (IOException error) { problems.add(error.getMessage()); }
        return toDiagnostics(new ArrayList<>(new LinkedHashSet<>(problems)));
    }
    private static List<Diagnostic> toDiagnostics(List<String> problems) {
        List<Diagnostic> out = new ArrayList<>();
        for (String problem : problems) {
            java.util.regex.Matcher matcher = Pattern.compile("^([^:]+):(\\d+):\\s*(.*)$").matcher(problem);
            int line = 0; String message = problem;
            if (matcher.matches()) { line = Math.max(0, Integer.parseInt(matcher.group(2)) - 1); message = matcher.group(1).equals("config") ? matcher.group(3) : matcher.group(1) + ": " + matcher.group(3); }
            out.add(new Diagnostic(line, message, "error"));
        }
        return out;
    }
    private static JSONArray diagnosticJson(List<Diagnostic> diagnostics) throws Exception {
        JSONArray result = new JSONArray(); for (Diagnostic diagnostic : diagnostics) result.put(new JSONObject().put("line", diagnostic.line).put("message", diagnostic.message).put("severity", diagnostic.severity)); return result;
    }
    private List<Line> expandIncludes(String content, File source, int depth, Set<String> stack, int[] budget) throws IOException {
        budget[0] += content.getBytes(StandardCharsets.UTF_8).length;
        if (depth > MAX_DEPTH || ++budget[1] > MAX_FILES || budget[0] > MAX_BYTES) throw new IOException("SSH Include expansion exceeds 8 levels, 32 files or 256 KiB.");
        String canonical = source.getCanonicalPath(); if (!stack.add(canonical)) throw new IOException("Recursive SSH Include: " + source.getName());
        try {
            List<Line> out = new ArrayList<>(); boolean insideBlock = false;
            for (Line line : parseLines(content, source)) {
                if (line.key.equals("host") || line.key.equals("match")) insideBlock = true;
                if (!line.key.equals("include")) { out.add(line); continue; }
                if (insideBlock) { out.add(includeError(line, "Include inside Host/Match is unsupported. Move Include before the first Host block.")); continue; }
                for (String pattern : line.values) {
                    try {
                        for (File included : includedFiles(pattern)) out.addAll(expandIncludes(readUtf8(included, MAX_BYTES), included, depth + 1, stack, budget));
                    } catch (IOException error) { out.add(includeError(line, error.getMessage())); }
                }
            }
            return out;
        } finally { stack.remove(canonical); }
    }
    private Line includeError(Line line, String message) throws IOException {
        return new Line("vstabincludeerror " + quote(line.location() + ": " + message) + "\n", line.source, line.number, line.start, line.end);
    }
    private List<File> includedFiles(String value) throws IOException {
        if (value.indexOf('%') >= 0 || value.contains("${")) throw new IOException("Include needs an explicit path inside the app's .ssh directory.");
        if (value.startsWith("~/")) value = new File(home, value.substring(2)).getAbsolutePath();
        else if (value.startsWith("~")) throw new IOException("Include supports only ~/ paths in the Android home.");
        File requested = new File(value); if (!requested.isAbsolute()) requested = new File(ssh, value);
        File directory = requested.getParentFile().getCanonicalFile();
        if (!directory.toPath().startsWith(ssh.toPath())) throw new IOException("Include must stay inside the app's .ssh directory.");
        if (directory.getPath().indexOf('*') >= 0 || directory.getPath().indexOf('?') >= 0) throw new IOException("Include wildcards in directory names are unsupported. Use an explicit app-private directory.");
        List<File> files = new ArrayList<>(); if (!directory.isDirectory()) return files;
        String leaf = requested.getName();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory.toPath(), leaf)) {
            for (Path entry : entries) {
                if (entry.getFileName().toString().startsWith(".") && !leaf.startsWith(".")) continue;
                File resolved = entry.toFile().getCanonicalFile();
                if (!resolved.toPath().startsWith(ssh.toPath())) throw new IOException("Include symbolic links must stay inside the app's .ssh directory.");
                if (resolved.isFile()) files.add(resolved);
                if (files.size() > MAX_FILES) throw new IOException("Include matches more than 32 files.");
            }
        } catch (java.nio.file.InvalidPathException | java.util.regex.PatternSyntaxException error) { throw new IOException("Invalid Include path pattern.", error); }
        files.sort(Comparator.comparing(File::getName)); return files;
    }

    private static List<Line> parseLines(String content, File source) throws IOException {
        List<Line> lines = new ArrayList<>(); int start = 0, number = 1;
        while (start < content.length()) { int end = content.indexOf('\n', start); end = end < 0 ? content.length() : end + 1; lines.add(new Line(content.substring(start, end), source, number++, start, end)); start = end; }
        return lines;
    }
    static List<String> tokenize(String value) throws IOException {
        List<String> out = new ArrayList<>(); StringBuilder token = new StringBuilder(); char quote = 0; boolean escaped = false, present = false;
        for (int at = 0; at < value.length(); at++) {
            char c = value.charAt(at);
            if (escaped) { token.append(c); escaped = false; present = true; continue; }
            if (c == '\\' && quote != '\'') { escaped = true; present = true; continue; }
            if (quote != 0) { if (c == quote) quote = 0; else token.append(c); present = true; continue; }
            if (c == '\'' || c == '"') { quote = c; present = true; continue; }
            if (c == '#') break;
            if (Character.isWhitespace(c)) { if (present) { out.add(token.toString()); token.setLength(0); present = false; } }
            else { token.append(c); present = true; }
        }
        if (quote != 0 || escaped) throw new IOException("Unterminated quote or escape in SSH configuration.");
        if (present) out.add(token.toString()); return out;
    }
    private static int commentOffset(String text) {
        char quote = 0; boolean escaped = false;
        for (int at = 0; at < text.length(); at++) { char c = text.charAt(at); if (escaped) { escaped = false; continue; } if (c == '\\' && quote != '\'') { escaped = true; continue; } if (quote != 0) { if (c == quote) quote = 0; } else if (c == '\'' || c == '"') quote = c; else if (c == '#') return at; }
        return -1;
    }
    private static boolean matches(String alias, List<String> patterns) {
        boolean positive = false;
        for (String pattern : patterns) { boolean negated = pattern.startsWith("!"); String raw = negated ? pattern.substring(1) : pattern; StringBuilder regex = new StringBuilder("^"); for (char c : raw.toCharArray()) regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c))); regex.append('$'); if (Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(alias).matches()) { if (negated) return false; positive = true; } }
        return positive;
    }
    static String idForAlias(String alias) { return "ssh-config-" + digest(alias.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)).substring(0, 24); }
    private static boolean literalAlias(String value) { return value != null && !value.isEmpty() && value.length() <= 255 && value.matches("[A-Za-z0-9_.:-]+") && !value.startsWith("-"); }
    private static boolean validEndpoint(String value) { return value != null && !value.isEmpty() && value.length() <= 255 && value.matches("[A-Za-z0-9_.:%-]+") && !value.startsWith("-"); }
    private String uniqueAlias(String target) throws IOException { String base = target.replaceAll("[^A-Za-z0-9_.-]", "-"); if (base.isEmpty() || base.startsWith("-")) base = "host" + base; if (base.length() > 240) base = base.substring(0, 240); String chosen = base; int suffix = 2; while (find(chosen) != null) chosen = base + "-" + suffix++; return chosen; }
    private static String quote(String value) throws IOException { if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IOException("SSH fields must fit on one line."); return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String displayKey(String key) { switch (key) { case "hostname": return "HostName"; case "user": return "User"; case "port": return "Port"; case "identityfile": return "IdentityFile"; case "identitiesonly": return "IdentitiesOnly"; default: return "PreferredAuthentications"; } }
    private static String appendBlock(String content, String block) { return content + (content.isEmpty() ? "" : content.endsWith("\n\n") ? "" : content.endsWith("\n") ? "\n" : "\n\n") + block; }
    private static String newline(String raw) { return raw.endsWith("\r\n") ? "\r\n" : "\n"; }
    private static List<Integer> explicitBlocks(List<Line> lines, String alias) { List<Integer> out = new ArrayList<>(); for (int at = 0; at < lines.size(); at++) if (lines.get(at).key.equals("host") && lines.get(at).values.stream().anyMatch(v -> v.equalsIgnoreCase(alias))) out.add(at); return out; }
    private static int blockEnd(List<Line> lines, int start) { int at = start + 1; while (at < lines.size() && !lines.get(at).key.equals("host") && !lines.get(at).key.equals("match")) at++; return at; }
    private void checkConfigLocation() throws IOException { if (Files.isSymbolicLink(config.toPath()) || !config.getCanonicalFile().toPath().startsWith(ssh.toPath())) throw new IOException("The canonical SSH config must be an ordinary file in the app's .ssh directory."); }
    private static String readUtf8(File file, int max) throws IOException {
        if (file.length() > max) throw new IOException("SSH config/Include is larger than 256 KiB.");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { if (output.size() + count > max) throw new IOException("SSH config/Include is larger than 256 KiB."); output.write(buffer, 0, count); }
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString(); }
        catch (CharacterCodingException error) { throw new IOException("SSH config must use UTF-8.", error); }
    }
    private static IOException conflict() { return new ConflictException(); }
    private static void privatePermissions(File file, boolean directory) throws IOException { if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false) || !file.setReadable(true, true) || !file.setWritable(true, true) || (directory && !file.setExecutable(true, true))) throw new IOException("Could not protect the app-private SSH configuration."); }
    private static String digest(byte[] bytes) { try { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
