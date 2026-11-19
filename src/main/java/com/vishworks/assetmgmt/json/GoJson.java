package com.vishworks.assetmgmt.json;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * A Jackson {@link ObjectMapper} configured to behave like Go's encoding/json, so that
 * every JSON document the Java backend produces (HTTP responses, process variables,
 * report files) is the same as the one the Go backend produced:
 *
 * <ul>
 *   <li>float64 numbers are written like strconv ('5000000', '0.82', never '5.0E7');
 *   <li>'&lt;', '&gt;', '&amp;', U+2028 and U+2029 are escaped as in json.Marshal (HTML-safe);
 *   <li>Postgres timestamps are written as RFC 3339 with nanoseconds, in UTC ('Z');
 *   <li>decoding ignores unknown fields, matches field names case-insensitively, keeps the
 *       zero value when a field is null, and rejects type mismatches (e.g. a number for a string).
 * </ul>
 */
public final class GoJson {
  private GoJson() {}

  public static final ObjectMapper MAPPER = build();

  private static ObjectMapper build() {
    SimpleModule m = new SimpleModule("go-json");
    JsonSerializer<Double> dbl = new JsonSerializer<>() {
      @Override
      public void serialize(Double v, JsonGenerator g, SerializerProvider p) throws IOException {
        try {
          g.writeNumber(com.vishworks.assetmgmt.util.GoFmt.jsonFloat(v));
        } catch (IllegalArgumentException e) {
          throw new com.fasterxml.jackson.core.JsonGenerationException(e.getMessage(), g);
        }
      }
    };
    m.addSerializer(Double.class, dbl);
    m.addSerializer(double.class, dbl);
    m.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
      @Override
      public void serialize(LocalDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
        g.writeString(com.vishworks.assetmgmt.util.GoFmt.rfc3339Nano(v));
      }
    });
    m.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
      @Override
      public void serialize(OffsetDateTime v, JsonGenerator g, SerializerProvider p) throws IOException {
        g.writeString(com.vishworks.assetmgmt.util.GoFmt.rfc3339Nano(v.toZonedDateTime()));
      }
    });

    JsonMapper mapper = JsonMapper.builder()
        .addModule(m)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(MapperFeature.AUTO_DETECT_GETTERS)
        .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .build();
    // Go leaves a field at its zero value when the JSON value is null.
    mapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.SKIP));
    // Go refuses to decode a number into a string, a string into a number, etc.
    for (LogicalType t : new LogicalType[] {LogicalType.Textual, LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
      mapper.coercionConfigFor(t)
          .setCoercion(CoercionInputShape.Integer, t == LogicalType.Integer || t == LogicalType.Float ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Float, t == LogicalType.Float ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.String, t == LogicalType.Textual ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.Boolean, t == LogicalType.Boolean ? CoercionAction.TryConvert : CoercionAction.Fail)
          .setCoercion(CoercionInputShape.EmptyString, t == LogicalType.Textual ? CoercionAction.TryConvert : CoercionAction.Fail);
    }
    mapper.getFactory().setCharacterEscapes(new GoEscapes());
    return mapper;
  }

  /** json.Marshal. */
  public static String marshal(Object v) {
    try {
      return MAPPER.writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalStateException("json: " + e.getMessage(), e);
    }
  }

  /** json.NewEncoder(w).Encode(v): compact JSON followed by a newline. */
  public static byte[] encode(Object v) {
    return (marshal(v) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** json.MarshalIndent(v, "", "  "). */
  public static String marshalIndent(Object v) {
    try {
      return MAPPER.writer(new GoPrettyPrinter()).writeValueAsString(v);
    } catch (IOException e) {
      throw new IllegalStateException("json: " + e.getMessage(), e);
    }
  }

  /** json.Unmarshal into a new value; a JSON null yields the zero value. */
  public static <T> T unmarshal(String json, Class<T> type) throws IOException {
    T v = MAPPER.readValue(json, type);
    if (v == null) {
      try {
        return type.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        return null;
      }
    }
    return v;
  }

  /**
   * json.NewDecoder(r).Decode(&v) for request bodies: an empty body is reported as "EOF"
   * (the Go error text), everything else as Jackson's parse error.
   */
  public static <T> T decodeBody(byte[] body, Class<T> type) throws DecodeException {
    String s = new String(body, StandardCharsets.UTF_8);
    if (s.isBlank()) {
      throw new DecodeException("EOF");
    }
    try {
      return unmarshal(s, type);
    } catch (IOException e) {
      String msg = e.getMessage();
      int nl = msg == null ? -1 : msg.indexOf('\n');
      throw new DecodeException(nl > 0 ? msg.substring(0, nl) : String.valueOf(msg));
    }
  }

  /** Error returned by {@link #decodeBody}. */
  public static final class DecodeException extends Exception {
    public DecodeException(String m) {
      super(m);
    }
  }

  /** encoding/json string escaping (HTML-safe, lower-case hex, \b and \f short forms). */
  static final class GoEscapes extends CharacterEscapes {
    private final int[] codes;

    GoEscapes() {
      codes = CharacterEscapes.standardAsciiEscapesForJSON();
      for (int c = 0; c < 0x20; c++) {
        codes[c] = CharacterEscapes.ESCAPE_CUSTOM;
      }
      codes['<'] = CharacterEscapes.ESCAPE_CUSTOM;
      codes['>'] = CharacterEscapes.ESCAPE_CUSTOM;
      codes['&'] = CharacterEscapes.ESCAPE_CUSTOM;
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return codes;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
      return switch (ch) {
        case '\n' -> new SerializedString("\\n");
        case '\r' -> new SerializedString("\\r");
        case '\t' -> new SerializedString("\\t");
        case '\b' -> new SerializedString("\\b");
        case '\f' -> new SerializedString("\\f");
        case 0x2028, 0x2029 -> new SerializedString(String.format("\\u%04x", ch));
        default -> ch < 0x20 || ch == '<' || ch == '>' || ch == '&'
            ? new SerializedString(String.format("\\u%04x", ch))
            : null;
      };
    }
  }

  /** Pretty printer reproducing json.MarshalIndent(v, "", "  "). */
  static final class GoPrettyPrinter extends DefaultPrettyPrinter {
    GoPrettyPrinter() {
      super(Separators.createDefaultInstance()
          .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
          .withRootSeparator(""));
      DefaultIndenter ind = new DefaultIndenter("  ", "\n");
      indentObjectsWith(ind);
      indentArraysWith(ind);
    }

    GoPrettyPrinter(GoPrettyPrinter base) {
      super(base);
    }

    @Override
    public DefaultPrettyPrinter createInstance() {
      return new GoPrettyPrinter(this);
    }

    @Override
    public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
      if (!_objectIndenter.isInline()) {
        --_nesting;
      }
      if (nrOfEntries > 0) {
        _objectIndenter.writeIndentation(g, _nesting);
      }
      g.writeRaw('}');
    }

    @Override
    public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
      if (!_arrayIndenter.isInline()) {
        --_nesting;
      }
      if (nrOfValues > 0) {
        _arrayIndenter.writeIndentation(g, _nesting);
      }
      g.writeRaw(']');
    }
  }
}
