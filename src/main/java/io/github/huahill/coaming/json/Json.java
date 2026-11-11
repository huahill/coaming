package io.github.huahill.coaming.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface Json {
    static Json parse(String text) {
        Parser parser = new Parser(text);
        Json value = parser.parseValue();
        parser.skip();
        if (!parser.done()) {
            throw new IllegalArgumentException("trailing JSON");
        }
        return value;
    }

    default Json field(String name) {
        if (this instanceof Obj obj) {
            Json value = obj.fields.get(name);
            if (value != null) {
                return value;
            }
        }
        throw new IllegalArgumentException("missing field " + name);
    }

    default Json optional(String name) {
        return this instanceof Obj obj ? obj.fields.get(name) : null;
    }

    default String string() {
        if (this instanceof Str str) {
            return str.value;
        }
        throw new IllegalArgumentException("expected string");
    }

    default boolean booleanValue() {
        if (this instanceof Bool bool) {
            return bool.value;
        }
        throw new IllegalArgumentException("expected boolean");
    }

    default long longValue() {
        if (this instanceof Num number) {
            return number.value;
        }
        throw new IllegalArgumentException("expected integer");
    }

    default List<String> strings() {
        if (!(this instanceof Arr arr)) {
            throw new IllegalArgumentException("expected array");
        }
        List<String> values = new ArrayList<>();
        for (Json item : arr.items) {
            values.add(item.string());
        }
        return values;
    }

    record Obj(Map<String, Json> fields) implements Json {}

    record Arr(List<Json> items) implements Json {}

    record Str(String value) implements Json {}

    record Num(long value) implements Json {}

    record Bool(boolean value) implements Json {}

    record Other() implements Json {}

    final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        Json parseValue() {
            skip();
            if (done()) {
                throw new IllegalArgumentException("empty JSON");
            }
            char ch = text.charAt(index);
            if (ch == '-' || (ch >= '0' && ch <= '9')) {
                return number();
            }
            return switch (ch) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Str(stringLiteral());
                case 't' -> word("true", new Bool(true));
                case 'f' -> word("false", new Bool(false));
                default -> {
                    skipLiteral();
                    yield new Other();
                }
            };
        }

        private Json word(String expected, Json value) {
            if (!text.startsWith(expected, index)) {
                throw new IllegalArgumentException("expected " + expected);
            }
            index += expected.length();
            return value;
        }

        private Json number() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (done() || !Character.isDigit(text.charAt(index))) {
                throw new IllegalArgumentException("expected number");
            }
            while (!done() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (!done()) {
                char ch = text.charAt(index);
                if (ch == '.' || ch == 'e' || ch == 'E') {
                    skipLiteral();
                    return new Other();
                }
            }
            return new Num(Long.parseLong(text.substring(start, index)));
        }

        private Json object() {
            index++;
            Map<String, Json> fields = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return new Obj(fields);
            }
            while (true) {
                skip();
                String key = stringLiteral();
                skip();
                expect(':');
                fields.put(key, parseValue());
                skip();
                if (peek('}')) {
                    index++;
                    return new Obj(fields);
                }
                expect(',');
            }
        }

        private Json array() {
            index++;
            List<Json> items = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return new Arr(items);
            }
            while (true) {
                items.add(parseValue());
                skip();
                if (peek(']')) {
                    index++;
                    return new Arr(items);
                }
                expect(',');
            }
        }

        private String stringLiteral() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!done()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    if (done()) {
                        break;
                    }
                    char escaped = text.charAt(index++);
                    value.append(switch (escaped) {
                        case '"', '\\', '/' -> escaped;
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'u' -> unicodeEscape();
                        default -> escaped;
                    });
                } else {
                    value.append(ch);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private char unicodeEscape() {
            int code = 0;
            for (int i = 0; i < 4; i++) {
                if (done()) {
                    throw new IllegalArgumentException("short unicode escape");
                }
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("bad unicode escape");
                }
                code = (code << 4) + digit;
            }
            return (char) code;
        }

        private void skipLiteral() {
            while (!done()) {
                char ch = text.charAt(index);
                if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
                    return;
                }
                index++;
            }
        }

        private void skip() {
            while (!done() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char ch) {
            return !done() && text.charAt(index) == ch;
        }

        private void expect(char ch) {
            if (!peek(ch)) {
                throw new IllegalArgumentException("expected '" + ch + "'");
            }
            index++;
        }

        private boolean done() {
            return index >= text.length();
        }
    }
}
