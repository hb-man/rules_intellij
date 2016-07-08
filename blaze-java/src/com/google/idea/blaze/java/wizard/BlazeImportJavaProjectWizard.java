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
package com.google.idea.blaze.java.wizard;

import com.intellij.ide.util.newProjectWizard.AddModuleWizard;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectImportProvider;

final class BlazeImportJavaProjectWizard extends AddModuleWizard {
  public BlazeImportJavaProjectWizard(VirtualFile file, ProjectImportProvider provider) {
    super(null, file.getCanonicalPath(), provider);
  }

  @Override
  protected String getDimensionServiceKey() {
    return null; // No dimension service
  }

  public boolean runWizard() {
    return showAndGet();
  }
}
