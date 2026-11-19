package com.vishworks.assetmgmt.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Formatting helpers that reproduce the Go standard library behaviour the original
 * backend relied on (fmt verbs, math.Round, strconv float formatting, time layouts).
 * Every text the Go code produced (log lines, timeline events, e-mail bodies, PDF
 * report) goes through these helpers so the Java output is identical.
 */
public final class GoFmt {
  private GoFmt() {}

  // ─── math ──────────────────────────────────────────────────────────────

  /** Go math.Round: round half away from zero (exact on the binary value). */
  public static double round(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) {
      return x;
    }
    double r = new BigDecimal(x).setScale(0, RoundingMode.HALF_UP).doubleValue();
    return (r == 0 && (x < 0 || 1 / x < 0)) ? -0.0 : r;
  }

  /** round2 from handlers.go: math.Round(f*100)/100. */
  public static double round2(double f) {
    return round(f * 100) / 100;
  }

  // ─── floats ────────────────────────────────────────────────────────────

  /** Go fmt %.{prec}f — the exact binary value rounded half-to-even (strconv semantics). */
  public static String fixed(double x, int prec) {
    if (Double.isNaN(x)) return "NaN";
    if (Double.isInfinite(x)) return x > 0 ? "+Inf" : "-Inf";
    String s = new BigDecimal(x).setScale(prec, RoundingMode.HALF_EVEN).toPlainString();
    boolean neg = x < 0 || (x == 0 && 1 / x < 0);
    if (neg && !s.startsWith("-")) s = "-" + s; // Go keeps the sign of -0 and of values rounding to 0
    return s;
  }

  /** strconv.FormatFloat(f, 'f', -1, 64): shortest representation, plain notation. */
  public static String shortestPlain(double x) {
    if (x == 0) return (1 / x < 0) ? "-0" : "0";
    return new BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString();
  }

  /** strconv.FormatFloat(f, 'e', -1, 64): shortest representation, exponent notation (e+06). */
  public static String shortestExp(double x) {
    if (x == 0) return ((1 / x < 0) ? "-" : "") + "0e+00";
    BigDecimal bd = new BigDecimal(Double.toString(x)).stripTrailingZeros();
    String digits = bd.unscaledValue().abs().toString();
    int exp = digits.length() - 1 - bd.scale();
    StringBuilder sb = new StringBuilder();
    if (bd.signum() < 0) sb.append('-');
    sb.append(digits.charAt(0));
    if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
    sb.append('e').append(exp < 0 ? '-' : '+');
    int a = Math.abs(exp);
    if (a < 10) sb.append('0');
    sb.append(a);
    return sb.toString();
  }

  /** encoding/json float64 encoding. */
  public static String jsonFloat(double f) {
    if (Double.isNaN(f) || Double.isInfinite(f)) {
      throw new IllegalArgumentException("json: unsupported value: " + fixedSpecial(f));
    }
    double abs = Math.abs(f);
    if (abs != 0 && (abs < 1e-6 || abs >= 1e21)) {
      String s = shortestExp(f);
      int n = s.length();
      // clean up e-09 to e-9 (as encoding/json does)
      if (n >= 4 && s.charAt(n - 4) == 'e' && s.charAt(n - 3) == '-' && s.charAt(n - 2) == '0') {
        s = s.substring(0, n - 2) + s.charAt(n - 1);
      }
      return s;
    }
    return shortestPlain(f);
  }

  private static String fixedSpecial(double f) {
    return Double.isNaN(f) ? "NaN" : (f > 0 ? "+Inf" : "-Inf");
  }

  /** fmt %v for a float64 (%g with the shortest representation). */
  public static String gFloat(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) return fixedSpecial(x);
    if (x == 0) return (1 / x < 0) ? "-0" : "0";
    BigDecimal bd = new BigDecimal(Double.toString(x)).stripTrailingZeros();
    String digits = bd.unscaledValue().abs().toString();
    int exp = digits.length() - 1 - bd.scale();
    if (exp < -4 || exp >= 21) {
      return shortestExp(x);
    }
    return shortestPlain(x);
  }

  // ─── fmt.Sprintf subset ────────────────────────────────────────────────

  /**
   * Subset of Go's fmt.Sprintf used by the original code: verbs %s %v %d %f %q %%,
   * flags '-' and '0', width and precision.
   */
  public static String sprintf(String format, Object... args) {
    StringBuilder out = new StringBuilder();
    int argi = 0;
    int n = format.length();
    for (int i = 0; i < n; i++) {
      char c = format.charAt(i);
      if (c != '%') {
        out.append(c);
        continue;
      }
      i++;
      if (i >= n) {
        out.append("%!(NOVERB)");
        break;
      }
      boolean minus = false, zero = false, plus = false;
      for (; i < n; i++) {
        char f = format.charAt(i);
        if (f == '-') minus = true;
        else if (f == '0') zero = !minus;
        else if (f == '+') plus = true;
        else break;
      }
      int width = -1;
      int start = i;
      while (i < n && Character.isDigit(format.charAt(i))) i++;
      if (i > start) width = Integer.parseInt(format.substring(start, i));
      int prec = -1;
      if (i < n && format.charAt(i) == '.') {
        i++;
        start = i;
        while (i < n && Character.isDigit(format.charAt(i))) i++;
        prec = i > start ? Integer.parseInt(format.substring(start, i)) : 0;
      }
      if (i >= n) {
        out.append("%!(NOVERB)");
        break;
      }
      char verb = format.charAt(i);
      if (verb == '%') {
        out.append('%');
        continue;
      }
      if (argi >= args.length) {
        out.append("%!").append(verb).append("(MISSING)");
        continue;
      }
      Object arg = args[argi++];
      String s;
      boolean numeric = false;
      switch (verb) {
        case 'd' -> {
          numeric = true;
          s = (arg instanceof Number num) ? Long.toString(num.longValue()) : "%!d(" + v(arg) + ")";
          if (plus && arg instanceof Number num && num.longValue() >= 0) s = "+" + s;
        }
        case 'f' -> {
          numeric = true;
          s = (arg instanceof Number num) ? fixed(num.doubleValue(), prec < 0 ? 6 : prec) : "%!f(" + v(arg) + ")";
          if (plus && !s.startsWith("-")) s = "+" + s;
        }
        case 'q' -> s = quote(String.valueOf(arg));
        case 's', 'v' -> {
          s = v(arg);
          if (prec >= 0 && verb == 's' && s.length() > prec) s = s.substring(0, prec);
        }
        default -> s = "%!" + verb + "(" + v(arg) + ")";
      }
      out.append(pad(s, width, minus, zero && numeric));
    }
    if (argi < args.length) {
      out.append("%!(EXTRA ");
      for (int k = argi; k < args.length; k++) {
        if (k > argi) out.append(", ");
        Object a = args[k];
        out.append(a == null ? "<nil>" : a.getClass().getSimpleName()).append('=').append(v(a));
      }
      out.append(')');
    }
    return out.toString();
  }

  private static String pad(String s, int width, boolean left, boolean zero) {
    int len = s.codePointCount(0, s.length());
    if (width < 0 || len >= width) return s;
    StringBuilder sb = new StringBuilder();
    int fill = width - len;
    if (left) {
      sb.append(s);
      sb.append(" ".repeat(fill));
      return sb.toString();
    }
    if (zero) {
      if (s.startsWith("-") || s.startsWith("+")) {
        return s.charAt(0) + "0".repeat(fill) + s.substring(1);
      }
      return "0".repeat(fill) + s;
    }
    return " ".repeat(fill) + s;
  }

  /** fmt %v. */
  public static String v(Object o) {
    if (o == null) return "<nil>";
    if (o instanceof Double d) return gFloat(d);
    if (o instanceof Float f) return gFloat(f);
    if (o instanceof Collection<?> c) {
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (Object e : c) {
        if (!first) sb.append(' ');
        sb.append(v(e));
        first = false;
      }
      return sb.append(']').toString();
    }
    if (o instanceof Map<?, ?> m) {
      StringBuilder sb = new StringBuilder("map[");
      boolean first = true;
      for (Map.Entry<?, ?> e : new java.util.TreeMap<>(m).entrySet()) {
        if (!first) sb.append(' ');
        sb.append(v(e.getKey())).append(':').append(v(e.getValue()));
        first = false;
      }
      return sb.append(']').toString();
    }
    return o.toString();
  }

  /** %v of a Go []string that may be nil. */
  public static String slice(List<String> l) {
    return v(l == null ? List.of() : l);
  }

  /** strconv.Quote (ASCII subset sufficient for identifiers). */
  public static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7f) sb.append(String.format("\\x%02x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.append('"').toString();
  }

  // ─── time ──────────────────────────────────────────────────────────────

  /** time.RFC3339 ("2006-01-02T15:04:05Z07:00") of an instant in UTC. */
  public static String rfc3339Utc(Instant t) {
    return rfc3339(t.atZone(ZoneOffset.UTC), false);
  }

  /** time.RFC3339Nano: fractional seconds with trailing zeros removed. */
  public static String rfc3339Nano(ZonedDateTime t) {
    return rfc3339(t, true);
  }

  /** pgx decodes a Postgres "timestamp" into a UTC time.Time; encoding/json uses RFC3339Nano. */
  public static String rfc3339Nano(LocalDateTime t) {
    return rfc3339(t.atZone(ZoneOffset.UTC), true);
  }

  private static String rfc3339(ZonedDateTime t, boolean nano) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%04d-%02d-%02dT%02d:%02d:%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
        t.getHour(), t.getMinute(), t.getSecond()));
    if (nano && t.getNano() != 0) {
      String frac = String.format("%09d", t.getNano());
      int end = frac.length();
      while (end > 0 && frac.charAt(end - 1) == '0') end--;
      sb.append('.').append(frac, 0, end);
    }
    int off = t.getOffset().getTotalSeconds();
    if (off == 0) {
      sb.append('Z');
    } else {
      sb.append(off < 0 ? '-' : '+');
      off = Math.abs(off);
      sb.append(String.format("%02d:%02d", off / 3600, (off / 60) % 60));
    }
    return sb.toString();
  }

  /** time.Duration.String() for a duration already rounded to whole seconds. */
  public static String duration(Duration d) {
    long secs = d.getSeconds();
    if (secs == 0 && d.getNano() == 0) return "0s";
    boolean neg = d.isNegative();
    secs = Math.abs(secs);
    long h = secs / 3600, m = (secs / 60) % 60, s = secs % 60;
    StringBuilder sb = new StringBuilder(neg ? "-" : "");
    if (h > 0) sb.append(h).append('h');
    if (h > 0 || m > 0) sb.append(m).append('m');
    sb.append(s).append('s');
    return sb.toString();
  }

  /** time.Duration.Round(time.Second). */
  public static Duration roundToSecond(Duration d) {
    long millis = d.toMillis();
    long secs = Math.floorDiv(millis + 500, 1000);
    return Duration.ofSeconds(secs);
  }
}
