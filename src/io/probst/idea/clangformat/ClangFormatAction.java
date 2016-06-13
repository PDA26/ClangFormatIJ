package io.probst.idea.clangformat;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs clang-format on the current statement or selection (if any), and applies the formatting
 * updates to the editor.
 */
public class ClangFormatAction extends AnAction {

  static final ExecutorService EXECUTOR = ForkJoinPool.commonPool();

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getData(CommonDataKeys.PROJECT);
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    e.getPresentation().setVisible((project != null && editor != null));
  }

  @Override
  public void actionPerformed(AnActionEvent actionEvent) {
    Project project = actionEvent.getData(CommonDataKeys.PROJECT);
    Editor editor = actionEvent.getData(CommonDataKeys.EDITOR);
    if (editor == null) return;  // can happen during startup.

    Document document = editor.getDocument();
    Caret caret = editor.getCaretModel().getPrimaryCaret();

    String filePath = actionEvent.getData(CommonDataKeys.VIRTUAL_FILE).getPath();
    // IntelliJ reports a cursor at the end of the file as being at file length + 1, which breaks
    // clang-format.
    int docLength = document.getTextLength() - 1;
    int cursor = Math.min(caret.getOffset(), docLength);
    int selectionStart = Math.min(editor.getSelectionModel().getSelectionStart(), docLength);
    int selectionLength = Math.min(editor.getSelectionModel().getSelectionEnd(), docLength)
        - selectionStart;

    Process formatter;
    try {
      // Comment in to debug mystifyingly missing binaries etc.
      //      System.out.println("PATH is " + readInput(new ProcessBuilder()
      //          .command("sh", "-c", "echo $PATH")
      //          .redirectErrorStream(true)
      //          .start()
      //          .getInputStream()));

      formatter = new ProcessBuilder()
          .command(
              "clang-format",
              "-output-replacements-xml",
              "-assume-filename=" + filePath,
              "-cursor=" + cursor,
              "-offset=" + selectionStart,
              "-length=" + selectionLength)
          .start();
    } catch (IOException e) {
      showError(project, "running clang-format failed - not installed?<br/>"
              + "Try running 'clang-format' in a shell.<br/>" + e.getMessage() + "<br>"
              + "<br>On Mac OS X, make sure to set your PATH variables in .profile, "
              + "not in e.g. .bash_profile.");
      return;
    }

    final OutputStream outputStream = formatter.getOutputStream();
    Future<?> outWritten = EXECUTOR.submit(() -> writeFileContents(document, outputStream));
    final InputStream inputStream = formatter.getInputStream();
    Future<Replacements> replacementsFuture = EXECUTOR.submit(() -> Replacements.parse(inputStream));
    final InputStream errorStream = formatter.getErrorStream();
    Future<String> errorMessage = EXECUTOR.submit(() -> readInput(errorStream));

    EXECUTOR.submit(() -> {
      try {
        try {
          outWritten.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
          showError(project, "timed out writing source file to clang-format");
          return;
        }
        if (!formatter.waitFor(5, TimeUnit.SECONDS)) {
          formatter.destroyForcibly();
          showError(project, "timed out waiting for clang-format to finish");
          return;
        }
        if (formatter.exitValue() != 0) {
          showError(project, "clang-format failed with exit code " + formatter.exitValue() +
              ", error: " + errorMessage.get());
          return;
        }
        final Replacements replacements = replacementsFuture.get();
        WriteCommandAction.runWriteCommandAction(project, () -> {
          // Track the actual location being moved by the insertions/removals.
          int offsetCorrection = 0;
          for (Replacement r : replacements.replacements) {
            int actualStart = r.offset + offsetCorrection;
            int actualEnd = actualStart + r.length;
            document.replaceString(actualStart, actualEnd, r.value);
            offsetCorrection -= r.length - r.value.length();
          }
          caret.moveToOffset(replacements.cursor);
        });
      } catch (InterruptedException e) {
        showError(project, e.getMessage());
      } catch (ExecutionException e) {
        showError(project, e.getCause().getMessage());
      }
    });
  }

  private void writeFileContents(Document document, OutputStream outputStream) {
    try (OutputStreamWriter out = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
      CharSequence contents = document.getImmutableCharSequence();
      out.append(contents);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String readInput(InputStream err) throws IOException {
    try (InputStreamReader errorStream = new InputStreamReader(err, StandardCharsets.UTF_8)) {
      StringBuilder sb = new StringBuilder();
      char[] buf = new char[4096];
      int read = 0;
      while ((read = errorStream.read(buf)) != -1) {
        sb.append(buf, 0, read);
      }
      return sb.toString();
    }
  }

  public static void showError(Project project, String errorMsg) {
    Notification notification =
        new Notification("ClangFormatIJ", "Formatting Failed", errorMsg, NotificationType.ERROR);
    Notifications.Bus.notify(notification, project);
  }

  @XmlRootElement
  static class Replacements {
    static final JAXBContext REPLACEMENTS_CTX;

    static {
      try {
        REPLACEMENTS_CTX = JAXBContext.newInstance(Replacements.class);
      } catch (JAXBException e) {
        throw new RuntimeException("Failed to load JAXB context", e);
      }
    }

    static Replacements parse(InputStream inputStream) {
      try {
        // JAXB closes the InputStream.
        return (Replacements) REPLACEMENTS_CTX.createUnmarshaller().unmarshal(inputStream);
      } catch (JAXBException e) {
        throw new RuntimeException("Failed to parse clang-format XML replacements", e);
      }
    }

    @XmlElement
    int cursor;
    @XmlElement(name = "replacement")
    List<Replacement> replacements;
  }

  static class Replacement {
    @XmlAttribute
    int offset;
    @XmlAttribute
    int length;
    @XmlValue
    String value;
  }
}
