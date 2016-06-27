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
package com.google.idea.blaze.base.actions;

import com.google.idea.blaze.base.settings.Blaze;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Base class action that hides for non-blaze projects.
 */
public abstract class BlazeAction extends AnAction {
  protected BlazeAction() {
  }

  protected BlazeAction(Icon icon) {
    super(icon);
  }

  protected BlazeAction(@Nullable String text) {
    super(text);
  }

  protected BlazeAction(@Nullable String text, @Nullable String description, @Nullable Icon icon) {
    super(text, description, icon);
  }

  @Override
  public final void update(AnActionEvent e) {
    if (!isBlazeProject(e)) {
      e.getPresentation().setEnabledAndVisible(false);
      return;
    }

    e.getPresentation().setEnabledAndVisible(true);
    doUpdate(e);
  }

  protected void doUpdate(@NotNull AnActionEvent e) {
  }

  private static boolean isBlazeProject(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    return project != null && Blaze.isBlazeProject(project);
  }
}
