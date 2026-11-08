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
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Str(stringLiteral());
                default -> {
                    skipLiteral();
                    yield new Other();
                }
            };
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
                        default -> escaped;
                    });
                } else {
                    value.append(ch);
                }
            }
            throw new IllegalArgumentException("unterminated string");
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
