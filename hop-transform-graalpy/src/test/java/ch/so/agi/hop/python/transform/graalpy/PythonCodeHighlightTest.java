package ch.so.agi.hop.python.transform.graalpy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PythonCodeHighlightTest {
  @Test
  void hashCommentIsStyledAsSingleCommentToken() {
    String line = "# return None";

    List<PythonCodeHighlight.TokenSpan> tokens = PythonCodeHighlight.tokenize(line);

    assertEquals(1, tokens.size());
    assertEquals(PythonCodeHighlight.TokenType.COMMENT, tokens.get(0).type());
    assertEquals(line, tokenText(line, tokens.get(0)));
  }

  @Test
  void inlineCommentStopsFurtherKeywordHighlighting() {
    String line = "return row  # return None";

    List<PythonCodeHighlight.TokenSpan> tokens = PythonCodeHighlight.tokenize(line);

    assertTrue(hasToken(line, tokens, PythonCodeHighlight.TokenType.KEYWORD, "return"));
    assertTrue(hasToken(line, tokens, PythonCodeHighlight.TokenType.COMMENT, "# return None"));
    assertFalse(hasTokenAfter(line, tokens, PythonCodeHighlight.TokenType.KEYWORD, "#"));
  }

  @Test
  void hashInsideStringDoesNotStartComment() {
    String line = "text = \"# not a comment\"";

    List<PythonCodeHighlight.TokenSpan> tokens = PythonCodeHighlight.tokenize(line);

    assertTrue(hasToken(line, tokens, PythonCodeHighlight.TokenType.STRING, "\"# not a comment\""));
    assertFalse(tokens.stream().anyMatch(token -> token.type() == PythonCodeHighlight.TokenType.COMMENT));
  }

  @Test
  void builtInFunctionsRemainHighlighted() {
    String line = "print(abs(value))";

    List<PythonCodeHighlight.TokenSpan> tokens = PythonCodeHighlight.tokenize(line);

    assertTrue(hasToken(line, tokens, PythonCodeHighlight.TokenType.FUNCTION, "print"));
    assertTrue(hasToken(line, tokens, PythonCodeHighlight.TokenType.FUNCTION, "abs"));
  }

  @Test
  void shebangIsHandledAsComment() {
    String line = "#!/usr/bin/env python";

    List<PythonCodeHighlight.TokenSpan> tokens = PythonCodeHighlight.tokenize(line);

    assertEquals(1, tokens.size());
    assertEquals(PythonCodeHighlight.TokenType.COMMENT, tokens.get(0).type());
    assertEquals(line, tokenText(line, tokens.get(0)));
  }

  private static boolean hasToken(
      String line, List<PythonCodeHighlight.TokenSpan> tokens, PythonCodeHighlight.TokenType type, String text) {
    return tokens.stream().anyMatch(token -> token.type() == type && tokenText(line, token).equals(text));
  }

  private static boolean hasTokenAfter(
      String line, List<PythonCodeHighlight.TokenSpan> tokens, PythonCodeHighlight.TokenType type, String marker) {
    int markerIndex = line.indexOf(marker);
    return tokens.stream().anyMatch(token -> token.type() == type && token.start() >= markerIndex);
  }

  private static String tokenText(String line, PythonCodeHighlight.TokenSpan token) {
    return line.substring(token.start(), token.end());
  }
}
