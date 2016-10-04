/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.java.libraries;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.intellij.codeInsight.AttachSourcesProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.psi.PsiFile;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** @author Sergey Evdokimov */
public class AddLibraryRuleDirectoryToProjectViewAttachSourcesProvider
    implements AttachSourcesProvider {

  @NotNull
  @Override
  public Collection<AttachSourcesAction> getActions(
      List<LibraryOrderEntry> orderEntries, final PsiFile psiFile) {
    Project project = psiFile.getProject();
    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (blazeProjectData == null) {
      return ImmutableList.of();
    }

    List<Library> librariesToAttachSourceTo = Lists.newArrayList();
    for (LibraryOrderEntry orderEntry : orderEntries) {
      Library library = orderEntry.getLibrary();
      WorkspacePath workspacePath =
          AddLibraryRuleDirectoryToProjectViewAction.getDirectoryToAddForLibrary(project, library);
      if (workspacePath == null) {
        continue;
      }
      librariesToAttachSourceTo.add(library);
    }

    if (librariesToAttachSourceTo.isEmpty()) {
      return ImmutableList.of();
    }

    return ImmutableList.of(
        new AttachSourcesAction() {
          @Override
          public String getName() {
            return "Add Source Directories To Project View";
          }

          @Override
          public String getBusyText() {
            return "Adding directories...";
          }

          @Override
          public ActionCallback perform(List<LibraryOrderEntry> orderEntriesContainingFile) {
            AddLibraryRuleDirectoryToProjectViewAction.addDirectoriesToProjectView(
                project, librariesToAttachSourceTo);
            return ActionCallback.DONE;
          }
        });
  }
}
