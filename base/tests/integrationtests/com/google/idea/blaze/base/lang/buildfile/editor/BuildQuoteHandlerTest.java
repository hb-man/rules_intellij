/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.lang.buildfile.editor;

import com.google.common.base.Joiner;
import com.google.idea.blaze.base.lang.buildfile.BuildFileIntegrationTestCase;
import com.google.idea.blaze.base.lang.buildfile.psi.BuildFile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for BuildQuoteHandler. */
@RunWith(JUnit4.class)
public class BuildQuoteHandlerTest extends BuildFileIntegrationTestCase {

  @Test
  public void testClosingQuoteInserted() {
    BuildFile file = createBuildFile("BUILD", "");

    performTypingAction(file, '"');
    assertFileContents(file, "\"\"");
  }

  @Test
  public void testClosingSingleQuoteInserted() {
    BuildFile file = createBuildFile("BUILD", "");

    performTypingAction(file, '\'');
    assertFileContents(file, "''");
  }

  @Test
  public void testClosingTripleQuoteInserted() {
    BuildFile file = createBuildFile("BUILD", "");

    performTypingAction(file, '"');
    performTypingAction(file, '"');
    performTypingAction(file, '"');
    assertFileContents(file, "\"\"\"\"\"\"");
  }

  @Test
  public void testClosingTripleSingleQuoteInserted() {
    BuildFile file = createBuildFile("BUILD", "");

    performTypingAction(file, '\'');
    performTypingAction(file, '\'');
    performTypingAction(file, '\'');
    assertFileContents(file, "''''''");
  }

  @Test
  public void testOnlyCaretMovedWhenCompletingExistingClosingQuotes() {
    BuildFile file = createBuildFile("BUILD", "'text<caret>'", "laterContents");

    testFixture.configureFromExistingVirtualFile(file.getVirtualFile());

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'text'<caret>", "laterContents"));
  }

  @Test
  public void testOnlyCaretMovedWhenCompletingExistingClosingTripleQuotes() {
    BuildFile file = createBuildFile("BUILD", "'''text<caret>'''", "laterContents");

    testFixture.configureFromExistingVirtualFile(file.getVirtualFile());

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'''text'<caret>''", "laterContents"));

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'''text''<caret>'", "laterContents"));

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'''text'''<caret>", "laterContents"));
  }

  @Test
  public void testAdditionalTripleQuotesNotInsertedWhenClosingQuotes() {
    BuildFile file = createBuildFile("BUILD", "'''text''<caret>", "laterContents");

    testFixture.configureFromExistingVirtualFile(file.getVirtualFile());

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'''text'''<caret>", "laterContents"));
  }

  @Test
  public void testAdditionalQuoteNotInsertedWhenClosingQuotes() {
    BuildFile file = createBuildFile("BUILD", "'text<caret>", "laterContents");

    testFixture.configureFromExistingVirtualFile(file.getVirtualFile());

    performTypingAction(file, '\'');

    testFixture.checkResult(Joiner.on("\n").join("'text'<caret>", "laterContents"));
  }
}
