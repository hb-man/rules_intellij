/*
 * Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android.run.binary;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.execution.common.debug.AndroidDebugger;
import com.android.tools.idea.execution.common.debug.AndroidDebuggerState;
import com.android.tools.idea.execution.common.debug.DebugSessionStarter;
import com.google.idea.blaze.android.run.runner.ApkBuildStep;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import javax.annotation.Nullable;
import kotlin.Unit;
import org.jetbrains.android.facet.AndroidFacet;

/** Compat class for {@link BlazeAndroidBinaryNormalBuildRunContext}. */
public class BlazeAndroidBinaryNormalBuildRunContextCompat
    extends BlazeAndroidBinaryNormalBuildRunContext {

  BlazeAndroidBinaryNormalBuildRunContextCompat(
      Project project,
      AndroidFacet facet,
      RunConfiguration runConfiguration,
      ExecutionEnvironment env,
      BlazeAndroidBinaryRunConfigurationState configState,
      ApkBuildStep buildStep,
      String launchId) {
    super(project, facet, runConfiguration, env, configState, buildStep, launchId);
  }

  @Nullable
  @Override
  public XDebugSession startDebuggerSession(
      AndroidDebugger androidDebugger,
      AndroidDebuggerState androidDebuggerState,
      ExecutionEnvironment env,
      IDevice device,
      ConsoleView consoleView,
      ProgressIndicator indicator,
      String packageName) {
    return DebugSessionStarter.INSTANCE.attachDebuggerToStartedProcess(
        device,
        packageName,
        env,
        androidDebugger,
        androidDebuggerState,
        /*destroyRunningProcess*/ d -> {
          d.forceStop(packageName);
          return Unit.INSTANCE;
        },
        indicator,
        consoleView,
        15L);
  }
}
