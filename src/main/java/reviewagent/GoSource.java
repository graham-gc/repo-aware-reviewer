package reviewagent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Lexical declaration extraction only: no project execution, type checking or build-tag selection. */
final class GoSource {
    record Token(String text, int start, int end, int line, int lastLine) { }
    record Declaration(String name, String kind, int first, int last) { }
    record Import(String alias, String path) { }
    record Parsed(String packageName, List<Import> imports, List<Declaration> declarations, List<String> constraints) { }

    static Parsed parse(String source) throws IOException {
        List<Token> tokens = tokens(source);
        var declarations = new ArrayList<Declaration>();
        var imports = new ArrayList<Import>();
        String packageName = "";
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            switch (token.text()) {
                case "package" -> { if (i + 1 < tokens.size()) packageName = tokens.get(++i).text(); }
                case "import" -> {
                    int end = statementEnd(tokens, i + 1);
                    for (int j = i + 1; j <= end; j++) {
                        Token part = tokens.get(j);
                        if (quoted(part.text())) {
                            String alias = j > i + 1 && identifier(tokens.get(j - 1).text())
                                    ? tokens.get(j - 1).text() : "";
                            if (j > i + 1 && tokens.get(j - 1).text().equals(".")) alias = ".";
                            imports.add(new Import(alias, unquote(part.text())));
                        }
                    }
                    i = end;
                }
                case "func" -> {
                    int cursor = i + 1;
                    String receiver = "";
                    if (cursor < tokens.size() && tokens.get(cursor).text().equals("(")) {
                        int endReceiver = matching(tokens, cursor);
                        List<Token> receiverTokens = tokens.subList(cursor + 1, endReceiver);
                        for (Token part : receiverTokens) {
                            if (part.text().equals("[")) break;
                            if (identifier(part.text())) receiver = part.text();
                        }
                        cursor = endReceiver + 1;
                    }
                    if (cursor >= tokens.size() || !identifier(tokens.get(cursor).text())) continue;
                    String name = tokens.get(cursor++).text();
                    if (!receiver.isEmpty()) name = receiver + "." + name;
                    if (cursor < tokens.size() && tokens.get(cursor).text().equals("[")) cursor = matching(tokens, cursor) + 1;
                    if (cursor >= tokens.size() || !tokens.get(cursor).text().equals("(")) throw new IOException("Incomplete Go function declaration");
                    int end = matching(tokens, cursor);
                    cursor = end + 1;
                    while (cursor < tokens.size()) {
                        String next = tokens.get(cursor).text();
                        if (next.equals("\n") || next.equals(";")) break;
                        if (next.equals("struct") || next.equals("interface")) {
                            cursor++;
                            if (cursor < tokens.size() && tokens.get(cursor).text().equals("{")) { end = matching(tokens, cursor); cursor = end + 1; }
                            continue;
                        }
                        if (next.equals("{")) { end = matching(tokens, cursor); break; }
                        if (next.equals("(") || next.equals("[")) { end = matching(tokens, cursor); cursor = end + 1; }
                        else { end = cursor++; }
                    }
                    declarations.add(new Declaration(name, receiver.isEmpty() ? "function" : "method", token.line(), tokens.get(end).lastLine()));
                    i = end;
                }
                case "type", "var", "const" -> {
                    int start = i + 1;
                    boolean grouped = start < tokens.size() && tokens.get(start).text().equals("(");
                    int end = statementEnd(tokens, start);
                    if (grouped) {
                        int cursor = start + 1;
                        while (cursor < end) {
                            if (!identifier(tokens.get(cursor).text())) { cursor++; continue; }
                            int last = declarationEnd(tokens, cursor, end);
                            addNames(declarations, tokens, cursor, last, token.text());
                            cursor = last + 1;
                        }
                    } else if (start < tokens.size() && identifier(tokens.get(start).text())) {
                        addNames(declarations, tokens, start, end, token.text());
                    }
                    i = end;
                }
                case "{" -> i = matching(tokens, i);
                default -> { }
            }
        }
        var constraints = source.lines().limit(50).filter(line -> line.stripLeading().startsWith("//go:build")
                || line.stripLeading().startsWith("// +build")).toList();
        return new Parsed(packageName, List.copyOf(imports), List.copyOf(declarations), constraints);
    }

    static LanguageModule.Lines locate(String source, String symbol) throws IOException {
        if (!symbol.matches("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)?"))
            throw new IOException("Expected a Go declaration name or Receiver.Method");
        var matches = parse(source).declarations().stream().filter(declaration -> declaration.name().equals(symbol)
                || (!symbol.contains(".") && declaration.name().endsWith("." + symbol))).toList();
        if (matches.isEmpty()) throw new IOException("Go declaration not found at that revision");
        if (matches.size() > 1) throw new IOException("Go symbol matches multiple declarations; qualify Receiver.Method or request a file/range");
        Declaration match = matches.getFirst();
        return new LanguageModule.Lines(match.first(), match.last());
    }

    private static void addNames(List<Declaration> output, List<Token> tokens, int start, int end, String kind) {
        int name = start;
        while (name <= end && identifier(tokens.get(name).text())) {
            output.add(new Declaration(tokens.get(name).text(), kind, tokens.get(start).line(), tokens.get(end).lastLine()));
            if (kind.equals("type") || name + 1 > end || !tokens.get(name + 1).text().equals(",")) break;
            name += 2;
        }
    }

    private static int declarationEnd(List<Token> tokens, int start, int bound) throws IOException {
        int end = start;
        for (int i = start; i < bound; i++) {
            String text = tokens.get(i).text();
            if (text.equals("\n") || text.equals(";")) break;
            if (text.equals("(") || text.equals("[") || text.equals("{")) i = matching(tokens, i);
            end = i;
        }
        return end;
    }

    private static int statementEnd(List<Token> tokens, int start) throws IOException {
        if (start >= tokens.size()) throw new IOException("Incomplete Go declaration");
        if (tokens.get(start).text().equals("(")) return matching(tokens, start);
        return declarationEnd(tokens, start, tokens.size());
    }

    private static int matching(List<Token> tokens, int start) throws IOException {
        var closes = new java.util.ArrayDeque<String>();
        for (int i = start; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            switch (text) {
                case "(" -> closes.push(")");
                case "[" -> closes.push("]");
                case "{" -> closes.push("}");
                case ")", "]", "}" -> {
                    if (closes.isEmpty() || !closes.pop().equals(text)) throw new IOException("Unbalanced Go declaration");
                    if (closes.isEmpty()) return i;
                }
                default -> { }
            }
        }
        throw new IOException("Unbalanced Go declaration");
    }

    static List<Token> tokens(String source) throws IOException {
        var tokens = new ArrayList<Token>();
        int line = 1;
        for (int i = 0; i < source.length();) {
            int start = i;
            int first = line;
            char c = source.charAt(i);
            if (c == '\n') { tokens.add(new Token("\n", i, ++i, line, line)); line++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i++) == '\n') line++;
                }
                if (i + 1 >= source.length()) throw new IOException("Unterminated Go block comment");
                i += 2;
                if (line > first) tokens.add(new Token("\n", start, i, first, line));
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i++;
                boolean closed = false;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == '\n') line++;
                    if (next == c) { closed = true; break; }
                    if (next == '\\' && c != '`') { if (i >= source.length()) break; i++; }
                }
                if (!closed) throw new IOException("Unterminated Go string or rune literal");
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
            } else i++;
            tokens.add(new Token(source.substring(start, i), start, i, first, line));
        }
        return List.copyOf(tokens);
    }

    private static boolean identifier(String text) { return text.matches("[\\p{L}_][\\p{L}\\p{N}_]*"); }
    private static boolean quoted(String text) { return text.startsWith("\"") || text.startsWith("`"); }
    static String unquote(String text) throws IOException {
        if (!quoted(text) || text.length() < 2) return text;
        String result = text.substring(1, text.length() - 1);
        if (result.contains("\\")) throw new IOException("Escaped import/module paths are not supported; request the source directly");
        return result;
    }
}
