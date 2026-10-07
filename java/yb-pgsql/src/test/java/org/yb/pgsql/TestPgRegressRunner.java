// Copyright (c) YugabyteDB, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.pgsql;

import static org.yb.AssertionWrappers.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.io.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;
import org.yb.BaseYBTest;
import org.yb.YBTestRunner;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@RunWith(value=YBTestRunner.class)
public class TestPgRegressRunner extends BaseYBTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private File write(File file, String content) throws Exception {
    Files.createDirectories(file.toPath().getParent());
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private String diffHeader(String options, File expectedFile, File resultFile) {
    return "diff " + options + " " + expectedFile + " " + resultFile;
  }

  @Test
  public void testCanonicalAlternateAndPlatformExpectedFiles() throws Exception {
    File expectedDir = temporaryFolder.newFolder("expected");
    File result = new File(temporaryFolder.getRoot(), "results/yb.orig.case.out");
    for (String name : Arrays.asList("yb.orig.case.out", "yb.orig.case_0.out",
        "yb.orig.case_9.out", "platform-specific.out")) {
      File expected = write(new File(expectedDir, name), "expected\n");
      assertEquals(expected, PgRegressRunner.findExpectedFile(
          Collections.singletonList(diffHeader("-U3", expected, result)), expectedDir, result));
    }
  }

  @Test
  public void testPathsWithSpacesAndCustomDiffOptions() throws Exception {
    File expectedDir = temporaryFolder.newFolder("output [case]+", "expected");
    File expected = write(new File(expectedDir, "platform [variant]+.out"), "expected\n");
    File result = new File(expectedDir.getParentFile(), "results/yb.orig.case.out");
    for (String options : Arrays.asList("-U3", "-w -U3", "-c", "", "--label='expected output'")) {
      assertEquals(expected, PgRegressRunner.findExpectedFile(
          Collections.singletonList(diffHeader(options, expected, result)), expectedDir, result));
    }
  }

  @Test
  public void testMultipleResultsAndDiffBody() throws Exception {
    File expectedDir = temporaryFolder.newFolder("expected");
    File expected1 = write(new File(expectedDir, "first_1.out"), "first\n");
    File expected2 = write(new File(expectedDir, "second_2.out"), "second\n");
    File result1 = new File(temporaryFolder.getRoot(), "results/first.out");
    File result2 = new File(temporaryFolder.getRoot(), "results/second.out");
    String header1 = diffHeader("-U3", expected1, result1);
    List<String> lines = Arrays.asList(
        "--- " + expected2, "+++ " + result1, "@@ -1 +1 @@",
        "-" + header1, "+" + header1, " " + header1,
        header1, diffHeader("-U3", expected2, result2));
    assertEquals(expected1, PgRegressRunner.findExpectedFile(lines, expectedDir, result1));
    assertEquals(expected2, PgRegressRunner.findExpectedFile(lines, expectedDir, result2));
  }

  @Test
  public void testMissingMalformedAndEscapingPaths() throws Exception {
    File expectedDir = temporaryFolder.newFolder("expected");
    File result = new File(temporaryFolder.getRoot(), "results/case.out");
    write(new File(temporaryFolder.getRoot(), "outside.out"), "outside\n");
    for (File candidate : Arrays.asList(new File(expectedDir, "missing.out"),
        new File(expectedDir, "../outside.out"))) {
      assertNull(PgRegressRunner.findExpectedFile(
          Collections.singletonList(diffHeader("-U3", candidate, result)), expectedDir, result));
    }
    assertNull(PgRegressRunner.findExpectedFile(Collections.emptyList(), expectedDir, result));
    assertNull(PgRegressRunner.findExpectedFile(
        Collections.singletonList("diff -U3 malformed " + result), expectedDir, result));
  }

  private void assertFailedRunUsesExpectedFile(boolean includeHeader) throws Exception {
    File inputDir = temporaryFolder.newFolder("input");
    write(new File(inputDir, "expected/case.out"), "canonical-only\nold\n");
    write(new File(inputDir, "expected/case_1.out"), "alternate-only\nalternate-detail\n");
    PgRegressRunner runner = new PgRegressRunner(
        inputDir, "runner [case]+ " + UUID.randomUUID(), 0);
    File expectedDir = new File(runner.outputDir(), "expected");
    FileUtils.copyDirectory(new File(inputDir, "expected"), expectedDir);
    File result = write(new File(runner.outputDir(), "results/case.out"),
        "alternate-only\nunexpected\n");
    File alternate = new File(expectedDir, "case_1.out");
    File selected = includeHeader ? alternate : new File(expectedDir, "case.out");
    File diffs = new File(runner.outputDir(), "regression.diffs");
    File failureLineRead = new File(temporaryFolder.getRoot(), "failure-line-read");

    // The child writes regression.diffs after run() clears it, then waits until LogPrinter has
    // consumed the failure line before exiting.
    ProcessBuilder process = new ProcessBuilder("bash", "-c",
        "printf 'test case ... FAILED\\n'; " +
        "if [ \"$4\" = true ]; then " +
        "printf 'diff %s %s %s\\n' '-U3' \"$2\" \"$3\" > \"$1\"; fi; " +
        "diff -U3 \"$2\" \"$3\" >> \"$1\"; " +
        "for ((attempt=0; attempt<1000; attempt++)); do " +
        "if [ -f \"$5\" ]; then exit 1; fi; sleep 0.01; done; exit 2",
        "pg_regress_stub", diffs.toString(), alternate.toString(), result.toString(),
        Boolean.toString(includeHeader), failureLineRead.toString());

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PgRegressRunner.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    PrintStream originalOut = System.out;
    AtomicReference<IOException> signalError = new AtomicReference<>();
    PrintStream signalingOut = new PrintStream(originalOut) {
      @Override
      public void println(String line) {
        super.println(line);
        if (line.endsWith("test case ... FAILED")) {
          try {
            Files.createFile(failureLineRead.toPath());
          } catch (IOException ex) {
            signalError.compareAndSet(null, ex);
          }
        }
      }
    };
    appender.start();
    logger.addAppender(appender);
    System.setOut(signalingOut);
    try {
      AssertionError failure = null;
      try {
        runner.run(process);
      } catch (AssertionError ex) {
        failure = ex;
      }
      if (signalError.get() != null) {
        throw signalError.get();
      }
      assertNotNull("The original regression failure must still be reported", failure);
      assertTrue(failure.getMessage().contains("pg_regress exited with error code: 1"));
      assertTrue(failure.getMessage().contains("failed tests: [case]"));

      List<String> messages = appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
      List<String> sideBySide = messages.stream()
          .filter(message -> message.startsWith("Side-by-side diff"))
          .collect(Collectors.toList());
      assertEquals(1, sideBySide.size());
      assertTrue(sideBySide.get(0).contains(selected.toString()));
      assertTrue(sideBySide.get(0).contains(includeHeader ? "alternate-detail" : "canonical-only"));
      if (includeHeader) {
        assertFalse(sideBySide.get(0).contains("canonical-only"));
      } else {
        assertTrue(messages.stream().anyMatch(message -> message.contains("falling back to")));
      }
    } finally {
      System.setOut(originalOut);
      signalingOut.flush();
      logger.detachAppender(appender);
      appender.stop();
      FileUtils.deleteDirectory(runner.outputDir());
    }
  }

  @Test
  public void testFailedRunUsesAlternateExpectedFile() throws Exception {
    assertFailedRunUsesExpectedFile(true);
  }

  @Test
  public void testFailedRunFallsBackWhenHeaderIsMissing() throws Exception {
    assertFailedRunUsesExpectedFile(false);
  }
}
