package ch.so.agi.hop.python.transform.graalpy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.core.widget.highlight.ScriptEngine;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;

final class PythonCodeHighlight implements LineStyleListener {
  private static final Set<String> KEYWORDS = new HashSet<>(ScriptEngine.PYTHON.getKeywords());
  private static final Set<String> BUILT_INS = new HashSet<>(ScriptEngine.PYTHON.getBuiltInFunctions());

  private final TokenStyle keywordStyle;
  private final TokenStyle commentStyle;
  private final TokenStyle stringStyle;
  private final TokenStyle numberStyle;
  private final TokenStyle functionStyle;

  PythonCodeHighlight() {
    GuiResource guiResource = GuiResource.getInstance();
    boolean darkMode = PropsUi.getInstance().isDarkMode();

    keywordStyle =
        new TokenStyle(
            darkMode ? guiResource.getColor(30, 144, 255) : guiResource.getColorBlue(), SWT.BOLD);
    commentStyle =
        new TokenStyle(
            darkMode ? guiResource.getColorGray() : guiResource.getColorDarkGray(), SWT.ITALIC);
    stringStyle = new TokenStyle(guiResource.getColorDarkGreen(), SWT.NORMAL);
    numberStyle = new TokenStyle(guiResource.getColorOrange(), SWT.NORMAL);
    functionStyle =
        new TokenStyle(
            darkMode ? guiResource.getColor(177, 102, 218) : guiResource.getColor(148, 0, 211),
            SWT.NORMAL);
  }

  @Override
  public void lineGetStyle(LineStyleEvent event) {
    if (event.lineText == null || event.lineText.isEmpty()) {
      return;
    }

    List<TokenSpan> tokens = tokenize(event.lineText);
    if (tokens.isEmpty()) {
      return;
    }

    List<StyleRange> styles = new ArrayList<>();
    for (TokenSpan token : tokens) {
      TokenStyle style = styleFor(token.type());
      if (style == null) {
        continue;
      }
      styles.add(
          new StyleRange(
              event.lineOffset + token.start(),
              token.length(),
              style.foreground(),
              null,
              style.fontStyle()));
    }

    if (!styles.isEmpty()) {
      event.styles = styles.toArray(new StyleRange[0]);
    }
  }

  static List<TokenSpan> tokenize(String line) {
    List<TokenSpan> tokens = new ArrayList<>();
    int index = 0;
    while (index < line.length()) {
      char current = line.charAt(index);

      if (current == '#') {
        tokens.add(new TokenSpan(TokenType.COMMENT, index, line.length()));
        break;
      }

      if (isStringStart(line, index)) {
        int end = consumeString(line, index);
        tokens.add(new TokenSpan(TokenType.STRING, index, end));
        index = end;
        continue;
      }

      if (Character.isDigit(current)) {
        int end = consumeNumber(line, index);
        tokens.add(new TokenSpan(TokenType.NUMBER, index, end));
        index = end;
        continue;
      }

      if (Character.isJavaIdentifierStart(current)) {
        int end = consumeIdentifier(line, index);
        if (end < line.length()
            && isStringQuote(line.charAt(end))
            && isStringPrefix(line.substring(index, end))) {
          end = consumeString(line, index);
          tokens.add(new TokenSpan(TokenType.STRING, index, end));
          index = end;
          continue;
        }

        String identifier = line.substring(index, end);
        if (KEYWORDS.contains(identifier)) {
          tokens.add(new TokenSpan(TokenType.KEYWORD, index, end));
        } else if (BUILT_INS.contains(identifier)) {
          tokens.add(new TokenSpan(TokenType.FUNCTION, index, end));
        }
        index = end;
        continue;
      }

      index++;
    }
    return tokens;
  }

  private TokenStyle styleFor(TokenType type) {
    return switch (type) {
      case COMMENT -> commentStyle;
      case STRING -> stringStyle;
      case NUMBER -> numberStyle;
      case KEYWORD -> keywordStyle;
      case FUNCTION -> functionStyle;
    };
  }

  private static boolean isStringStart(String line, int index) {
    if (index >= line.length()) {
      return false;
    }
    char current = line.charAt(index);
    if (isStringQuote(current)) {
      return true;
    }

    int identifierEnd = consumeIdentifier(line, index);
    return identifierEnd > index
        && identifierEnd < line.length()
        && isStringQuote(line.charAt(identifierEnd))
        && isStringPrefix(line.substring(index, identifierEnd));
  }

  private static int consumeString(String line, int start) {
    int index = start;
    if (!isStringQuote(line.charAt(index))) {
      index = consumeIdentifier(line, index);
    }

    char quote = line.charAt(index);
    index++;
    boolean escaped = false;
    while (index < line.length()) {
      char current = line.charAt(index);
      if (escaped) {
        escaped = false;
        index++;
        continue;
      }
      if (current == '\\') {
        escaped = true;
        index++;
        continue;
      }
      if (current == quote) {
        index++;
        break;
      }
      index++;
    }
    return index;
  }

  private static int consumeNumber(String line, int start) {
    int index = start;
    while (index < line.length()) {
      char current = line.charAt(index);
      if (Character.isDigit(current) || current == '_' || current == '.') {
        index++;
        continue;
      }
      if ((current == 'e' || current == 'E')
          && index + 1 < line.length()
          && Character.isDigit(line.charAt(index + 1))) {
        index += 2;
        continue;
      }
      if ((current == '+' || current == '-')
          && index > start
          && (line.charAt(index - 1) == 'e' || line.charAt(index - 1) == 'E')
          && index + 1 < line.length()
          && Character.isDigit(line.charAt(index + 1))) {
        index += 2;
        continue;
      }
      break;
    }
    return index;
  }

  private static int consumeIdentifier(String line, int start) {
    int index = start;
    while (index < line.length() && Character.isJavaIdentifierPart(line.charAt(index))) {
      index++;
    }
    return index;
  }

  private static boolean isStringPrefix(String prefix) {
    if (prefix.isEmpty() || prefix.length() > 2) {
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      char current = Character.toLowerCase(prefix.charAt(i));
      if (current != 'r' && current != 'b' && current != 'u' && current != 'f') {
        return false;
      }
    }
    return true;
  }

  private static boolean isStringQuote(char character) {
    return character == '\'' || character == '"';
  }

  enum TokenType {
    COMMENT,
    STRING,
    NUMBER,
    KEYWORD,
    FUNCTION
  }

  record TokenSpan(TokenType type, int start, int end) {
    int length() {
      return end - start;
    }
  }

  private record TokenStyle(Color foreground, int fontStyle) {}
}
