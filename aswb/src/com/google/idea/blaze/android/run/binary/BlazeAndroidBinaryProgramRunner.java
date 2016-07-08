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
package com.google.idea.blaze.android.run.binary;

import com.android.tools.idea.fd.InstantRunUtils;
import com.android.tools.idea.run.AndroidSessionInfo;
import com.google.idea.blaze.android.run.binary.mobileinstall.IncrementalInstallDebugExecutor;
import com.google.idea.blaze.android.run.binary.mobileinstall.IncrementalInstallRunExecutor;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.DefaultProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import org.jetbrains.annotations.NotNull;

/**
 * Program runner for {@link BlazeAndroidBinaryRunConfiguration}
 */
public class BlazeAndroidBinaryProgramRunner extends DefaultProgramRunner {
  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    if (!(profile instanceof BlazeAndroidBinaryRunConfiguration)) {
      return false;
    }
    BlazeAndroidBinaryRunConfiguration runConfiguration = (BlazeAndroidBinaryRunConfiguration) profile;
    if (runConfiguration.getConfigState().isMobileInstall()) {
      return (IncrementalInstallDebugExecutor.EXECUTOR_ID.equals(executorId)
              || IncrementalInstallRunExecutor.EXECUTOR_ID.equals(executorId));
    }

    return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) || DefaultRunExecutor.EXECUTOR_ID.equals(executorId);
  }

  @Override
  protected RunContentDescriptor doExecute(@NotNull final RunProfileState state, @NotNull final ExecutionEnvironment env)
    throws ExecutionException {
    RunContentDescriptor descriptor = super.doExecute(state, env);
    if (descriptor != null) {
      ProcessHandler processHandler = descriptor.getProcessHandler();
      assert processHandler != null;

      RunProfile runProfile = env.getRunProfile();
      int uniqueId = (runProfile instanceof BlazeAndroidBinaryRunConfiguration)
                     ? ((BlazeAndroidBinaryRunConfiguration)runProfile).getUniqueID() : -1;
      AndroidSessionInfo sessionInfo = new AndroidSessionInfo(processHandler, descriptor, uniqueId, env.getExecutor().getId(),
                                                              InstantRunUtils.isInstantRunEnabled(env));
      processHandler.putUserData(AndroidSessionInfo.KEY, sessionInfo);
    }

    return descriptor;
  }

  @Override
  @NotNull
  public String getRunnerId() {
    return "AndroidProgramRunner";
  }
}
