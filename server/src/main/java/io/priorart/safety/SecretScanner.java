package io.priorart.safety;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence before text becomes public: rejects anything that looks like a credential or internal
 * infrastructure. Authoritative (the client-side scan in the skill is advisory and can be bypassed by calling the API
 * directly). Keep rules in sync with plugins/priorart/skills/priorart/scripts/priorart-secrets.mjs.
 *
 * Text is scanned in several views, because obfuscation is cheap: as written; with zero-width/format characters removed
 * and NFKC-normalised; with Cyrillic/Greek lookalikes mapped to Latin ("АKIA" with a Cyrillic А); with spaced-out
 * character runs ("A K I A …", "10 . 0 . 0 . 5") and line-broken tokens joined; with base64- and hex-looking tokens
 * decoded; across field boundaries; and with all fields run together without spaces (a key split over 3+ fields).
 */
public final class SecretScanner {
    private SecretScanner() {}

    public record Finding(String kind, String preview) {}

    private record Rule(String kind, Pattern pattern) {}

    private static final String SECRETISH = "(?=\\S*[0-9!@#$%^&*_+=])\\S{6,}"; // a value with a digit or symbol, 6+ chars

    private static final List<Rule> RULES = List.of(
            new Rule("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new Rule("AWS access key", Pattern.compile("(?i)\\b(AKIA|ASIA)(?=[0-9A-Z]*[0-9])[0-9A-Z]{16}\\b")),
            new Rule("GitHub token", Pattern.compile("\\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b|\\bgithub_pat_[A-Za-z0-9_]{40,}\\b")),
            new Rule("GitLab token", Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b")),
            new Rule("Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b")),
            new Rule("OpenAI/Anthropic key", Pattern.compile("\\bsk-(ant-|proj-)?[A-Za-z0-9_-]{20,}\\b")),
            new Rule("Stripe key", Pattern.compile("\\b(sk|rk|pk)_(live|test)_[A-Za-z0-9]{16,}\\b")),
            new Rule("Google API key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new Rule("JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")),
            new Rule("password assignment", Pattern.compile("(?i)\\b(password|passwd|pwd|passphrase|secret|api[_-]?key|token)\\s*[:=]\\s*['\"]?[^\\s'\"]{6,}")),
            new Rule("password in prose", Pattern.compile("(?i)\\b(password|passwd|passphrase|secret|api[ _-]?key|token)\\s+(is|was|=|:)\\s*['\"]?" + SECRETISH)),
            new Rule("connection string with credentials", Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:[^\\s/@]+@")),
            new Rule("private IP address", Pattern.compile("\\b(10\\.\\d{1,3}|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.\\d{1,3}\\.\\d{1,3}\\b")),
            new Rule("internal hostname", Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)+(internal|corp|local|lan|intranet|intra|private|priv|home\\.arpa)\\b")),
            new Rule("email address", Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")));

    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u2028-\\u202F\\u2060-\\u206F\\uFEFF]");
    /** A run of 6+ single characters separated by spaces, standing alone: "A K I A I O …". */
    private static final Pattern SPACED_RUN = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Za-z0-9_][ \\t]+){5,}[A-Za-z0-9_](?![A-Za-z0-9])");
    /** Spaces around dots between digits: "10 . 0 . 0 . 5". */
    private static final Pattern SPACED_DOT = Pattern.compile("(?<=\\d)[ \\t]*\\.[ \\t]*(?=\\d)");
    private static final Pattern BROKEN_TOKEN = Pattern.compile("([A-Za-z0-9_])[ \\t]*\\r?\\n[ \\t]*([A-Za-z0-9_])");
    private static final Pattern BASE64ISH = Pattern.compile("[A-Za-z0-9+/_-]{20,}={0,2}");
    private static final Pattern HEXISH = Pattern.compile("\\b(?:[0-9a-fA-F]{2}){12,}\\b");

    /** Cyrillic and Greek letters that look like Latin ones. */
    private static final Map<Character, Character> LOOKALIKE = new HashMap<>();
    static {
        String from = "АВЕКМНОРСТХУІЈЅԁаеорсхуіјѕһԛԝΑΒΕΖΗΙΚΜΝΟΡΤΥΧαοικνρτυχ";
        String to   = "ABEKMHOPCTXYIJSdaeopcxyijshqwABEZHIKMNOPTYXaoiknptux";
        for (int i = 0; i < from.length(); i++) LOOKALIKE.put(from.charAt(i), to.charAt(i));
    }

    private static String skeleton(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) sb.append(LOOKALIKE.getOrDefault(c, c));
        return sb.toString();
    }

    /** Scans one text. */
    public static List<Finding> scan(String text) {
        return scanFields(text == null ? List.of() : List.of(text));
    }

    /** Scans several fields together, including across their boundaries. */
    public static List<Finding> scanFields(List<String> fields) {
        LinkedHashMap<String, Finding> out = new LinkedHashMap<>();
        List<String> clean = fields.stream().filter(Objects::nonNull).map(SecretScanner::normalise).toList();
        for (String view : views(clean)) find(view, RULES, out);
        // Gluing fields together only makes sense for tokens: "10.2" next to "20.11" is two version numbers, not the
        // address 10.210.220.11.
        List<Rule> tokens = RULES.stream().filter(r -> SPLITTABLE.contains(r.kind())).toList();
        for (String view : splitViews(clean)) find(view, tokens, out);
        return new ArrayList<>(out.values());
    }

    private static void find(String view, List<Rule> rules, LinkedHashMap<String, Finding> out) {
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(view);
            // one finding per secret, however many views it shows up in
            while (m.find() && out.size() < 20)
                out.putIfAbsent(r.kind() + "|" + m.group().replaceAll("\\s+", "").toLowerCase(Locale.ROOT), new Finding(r.kind(), mask(m.group())));
        }
    }

    private static String normalise(String s) {
        return skeleton(INVISIBLE.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll(""));
    }

    private static List<String> views(List<String> fields) {
        List<String> v = new ArrayList<>();
        String all = String.join("\n", fields);
        v.add(all);
        v.add(SPACED_DOT.matcher(BROKEN_TOKEN.matcher(collapseSpacedRuns(all)).replaceAll("$1$2")).replaceAll("."));
        for (Matcher m = BASE64ISH.matcher(all); m.find(); ) {
            String decoded = decode(m.group());
            if (decoded != null) v.add(decoded);
        }
        for (Matcher m = HEXISH.matcher(all); m.find(); ) {
            String decoded = decodeHex(m.group());
            if (decoded != null) v.add(decoded);
        }
        return v;
    }

    /** Kinds that are single tokens, so a split across fields can be glued back: keys, tokens, JWTs. */
    private static final Set<String> SPLITTABLE = Set.of("private key", "AWS access key", "GitHub token", "GitLab token",
            "Slack token", "OpenAI/Anthropic key", "Stripe key", "Google API key", "JWT");

    /**
     * A token split across fields: the end of one field, then up to two short fragment fields, then the start of
     * another, in any order. Fragments are what a split leaves behind (≤ 40 chars), so the combinations stay small.
     */
    private static List<String> splitViews(List<String> fields) {
        List<String> v = new ArrayList<>();
        List<String> frags = fields.stream().map(String::strip).filter(f -> !f.isEmpty() && f.length() <= 40).toList();
        for (int i = 0; i < fields.size(); i++)
            for (int j = 0; j < fields.size(); j++) {
                if (i == j) continue;
                String a = tail(fields.get(i)), b = head(fields.get(j));
                v.add(a + b);
                for (String f1 : frags) {
                    v.add(a + f1 + b);
                    for (String f2 : frags) if (!f2.equals(f1)) v.add(a + f1 + f2 + b);
                }
            }
        return v;
    }



    private static String collapseSpacedRuns(String s) {
        Matcher m = SPACED_RUN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[ \\t]+", "")));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Base64 / base64url that decodes to mostly printable text; null otherwise. */
    private static String decode(String token) {
        try {
            byte[] b = token.contains("-") || token.contains("_") ? Base64.getUrlDecoder().decode(token.replace("=", ""))
                                                                  : Base64.getDecoder().decode(token);
            String s = new String(b, StandardCharsets.UTF_8);
            long printable = s.chars().filter(c -> c >= 32 && c < 127).count();
            return s.length() >= 8 && printable >= s.length() * 0.9 ? s : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decodeHex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        String s = new String(b, StandardCharsets.UTF_8);
        long printable = s.chars().filter(c -> c >= 32 && c < 127).count();
        return printable >= s.length() * 0.9 ? s : null;
    }

    private static String tail(String s) { String t = s.strip(); return t.substring(Math.max(0, t.length() - 60)); }
    private static String head(String s) { String t = s.strip(); return t.substring(0, Math.min(60, t.length())); }

    private static String mask(String s) {
        return s.length() <= 8 ? "****" : s.substring(0, 4) + "…" + "*".repeat(Math.min(8, s.length() - 4));
    }

    // ---- dangerous instructions (not blocked: shown to readers as specific warnings) ---------------------------

    private static final List<Rule> DANGER = List.of(
            new Rule("pipes a download straight into a shell", Pattern.compile("(?i)\\b(curl|wget|iwr|invoke-webrequest)\\b[^\\n|]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|python3?|iex|powershell)\\b")),
            new Rule("decodes and executes hidden content", Pattern.compile("(?i)base64\\s+(-d|--decode)[^\\n|]*\\|\\s*(sh|bash)")),
            new Rule("disables TLS/certificate verification", Pattern.compile("(?i)NODE_TLS_REJECT_UNAUTHORIZED\\s*=\\s*['\"]?0|verify\\s*=\\s*False|--insecure\\b|\\bcurl\\s+(-\\w+\\s+)*-k\\b|sslmode\\s*=\\s*disable|InsecureSkipVerify\\s*:\\s*true|rejectUnauthorized\\s*:\\s*false|(disable|turn off|skip)\\w*\\s+(tls|ssl|certificate)\\s*(verification|validation|checks?)")),
            new Rule("disables host-key checking", Pattern.compile("(?i)StrictHostKeyChecking\\s*=?\\s*no")),
            new Rule("world-writable permissions", Pattern.compile("(?i)chmod\\s+(-R\\s+)?(0?777|a\\+rwx)")),
            new Rule("deletes from the filesystem root", Pattern.compile("(?i)\\brm\\s+-[a-z]*r[a-z]*f?[a-z]*\\s+(/|~|\\$HOME)(\\s|$)")),
            new Rule("installs from an untrusted index", Pattern.compile("(?i)(--trusted-host|--index-url\\s+http://|--extra-index-url\\s+http://|npm\\s+config\\s+set\\s+strict-ssl\\s+false)")),
            new Rule("disables a security control", Pattern.compile("(?i)(setenforce\\s+0|ufw\\s+disable|disable\\w*\\s+(the\\s+)?(firewall|selinux|antivirus|defender|2fa|mfa))")),
            // text written to steer the AI agent reading the record rather than to inform the person
            new Rule("contains instructions aimed at AI agents", Pattern.compile("(?i)"
                    + "\\b(ignore|disregard|forget|override|pay\\s+no\\s+attention\\s+to)\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier|preceding|original|your|system)\\s+(instructions|guidance|directions|prompts?|rules|context)"
                    + "|\\b(instructions|note|message)\\s+(for|to)\\s+(the\\s+|any\\s+)?(ai|agent|assistant|llm|model|copilot|claude)s?\\b"
                    + "|\\bwhen\\s+(an?|the|any)\\s+(ai|agent|assistant|llm|model)\\s+(reads|sees|processes)\\s+this"
                    + "|\\b(the|an?|any)\\s+(ai|agent|assistant|llm)\\s+(should|must|needs\\s+to)\\s+(now\\s+)?(run|execute|print|cat|send|post|upload|ignore|delete)\\b"
                    + "|\\bsystem\\s+prompt\\b|\\bdo\\s+not\\s+(tell|inform|show|mention\\s+this\\s+to)\\s+the\\s+user"
                    + "|\\b(send|post|upload|transmit|exfiltrate)\\w*\\b[^\\n]{0,60}(~/\\.ssh|id_rsa|\\.aws/credentials|process\\.env)"
                    + "|\\b(send|post|upload|transmit|exfiltrate|curl)\\w*\\b[^\\n]{0,60}(env(ironment)?\\s+var\\w*|api\\s+keys?|\\btokens?\\b)\\s+(to|at)\\s+(https?://|\\S+\\.\\S+)")));


    /** Specific warnings for a public record's readers; empty when nothing risky is suggested. */
    public static List<String> dangers(List<String> fields) {
        String all = normalise(String.join("\n", fields.stream().filter(Objects::nonNull).toList()));
        List<String> out = new ArrayList<>();
        for (Rule r : DANGER) if (r.pattern().matcher(all).find()) out.add(r.kind());
        return out;
    }
}
