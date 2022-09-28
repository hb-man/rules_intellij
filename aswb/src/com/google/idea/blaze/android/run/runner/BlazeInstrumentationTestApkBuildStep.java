/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android.run.runner;

import com.android.tools.idea.run.ApkProvisionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.AndroidDeployInfo;
import com.google.idea.blaze.android.run.deployinfo.BlazeAndroidDeployInfo;
import com.google.idea.blaze.android.run.deployinfo.BlazeApkDeployInfoProtoHelper;
import com.google.idea.blaze.android.run.deployinfo.BlazeApkDeployInfoProtoHelper.GetDeployInfoException;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper.GetArtifactsException;
import com.google.idea.blaze.base.console.BlazeConsoleLineProcessorProvider;
import com.google.idea.blaze.base.filecache.FileCaches;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.AndroidInstrumentationInfo;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.output.StatusOutput;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.sync.aspects.BlazeBuildOutputs;
import com.google.idea.blaze.base.sync.aspects.BuildResult;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.util.SaveUtil;
import com.google.idea.blaze.java.AndroidBlazeRules.RuleTypes;
import com.intellij.openapi.project.Project;
import java.io.File;
import javax.annotation.Nullable;

/** Builds the APKs required for an android instrumentation test. */
public class BlazeInstrumentationTestApkBuildStep implements ApkBuildStep {

  /** Subject to change with changes to android build rules. */
  private static final String DEPLOY_INFO_FILE_SUFFIX = ".deployinfo.pb";

  private final Project project;
  private final Label instrumentationTestLabel;
  private final ImmutableList<String> buildFlags;
  private final BlazeApkDeployInfoProtoHelper deployInfoHelper;
  private BlazeAndroidDeployInfo deployInfo = null;

  /**
   * Note: Target kind of {@param instrumentationTestlabel} must be "android_instrumentation_test".
   */
  public BlazeInstrumentationTestApkBuildStep(
      Project project, Label instrumentationTestLabel, ImmutableList<String> buildFlags) {
    this(project, instrumentationTestLabel, buildFlags, new BlazeApkDeployInfoProtoHelper());
  }

  @VisibleForTesting
  public BlazeInstrumentationTestApkBuildStep(
      Project project,
      Label instrumentationTestLabel,
      ImmutableList<String> buildFlags,
      BlazeApkDeployInfoProtoHelper deployInfoHelper) {
    this.project = project;
    this.instrumentationTestLabel = instrumentationTestLabel;
    this.buildFlags = buildFlags;
    this.deployInfoHelper = deployInfoHelper;
  }

  @Override
  public void build(BlazeContext context, BlazeAndroidDeviceSelector.DeviceSession deviceSession) {
    BlazeProjectData projectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();
    if (projectData == null) {
      IssueOutput.error("Invalid project data. Please sync the project.").submit(context);
      return;
    }

    InstrumentationInfo testComponents =
        getInstrumentationInfo(instrumentationTestLabel, projectData, context);
    if (testComponents == null) {
      return;
    }

    BuildInvoker invoker =
        Blaze.getBuildSystemProvider(project).getBuildSystem().getBuildInvoker(project, context);
    BlazeCommand.Builder command = BlazeCommand.builder(invoker, BlazeCommandName.BUILD);
    WorkspaceRoot workspaceRoot = WorkspaceRoot.fromProject(project);

    // TODO(mathewi) we implicitly rely here on the fact that the getBuildInvoker() call above
    //   will always return a local invoker (deployInfoHelper below required that the artifacts
    //   are on the local filesystem).
    try (BuildResultHelper buildResultHelper = invoker.createBuildResultHelper()) {
      if (testComponents.isSelfInstrumentingTest()) {
        command.addTargets(testComponents.testApp);
      } else {
        command.addTargets(testComponents.targetApp, testComponents.testApp);
      }
      command
          .addBlazeFlags("--output_groups=+android_deploy_info")
          .addBlazeFlags(buildFlags)
          .addBlazeFlags(buildResultHelper.getBuildFlags());

      SaveUtil.saveAllFiles();
      int retVal =
          ExternalTask.builder(workspaceRoot)
              .addBlazeCommand(command.build())
              .context(context)
              .stderr(
                  LineProcessingOutputStream.of(
                      BlazeConsoleLineProcessorProvider.getAllStderrLineProcessors(context)))
              .build()
              .run();
      ListenableFuture<Void> unusedFuture =
          FileCaches.refresh(
              project, context, BlazeBuildOutputs.noOutputs(BuildResult.fromExitCode(retVal)));

      if (retVal != 0) {
        IssueOutput.error("Blaze build failed. See Blaze Console for details.").submit(context);
        return;
      }
      try {
        context.output(new StatusOutput("Reading deployment information..."));
        String executionRoot =
            ExecRootUtil.getExecutionRoot(buildResultHelper, project, buildFlags, context);
        if (executionRoot == null) {
          IssueOutput.error("Could not locate execroot!").submit(context);
          return;
        }

        AndroidDeployInfo instrumentorDeployInfoProto =
            deployInfoHelper.readDeployInfoProtoForTarget(
                testComponents.testApp,
                buildResultHelper,
                fileName -> fileName.endsWith(DEPLOY_INFO_FILE_SUFFIX));
        if (testComponents.isSelfInstrumentingTest()) {
          deployInfo =
              deployInfoHelper.extractDeployInfoAndInvalidateManifests(
                  project, new File(executionRoot), instrumentorDeployInfoProto);
        } else {
          AndroidDeployInfo targetDeployInfoProto =
              deployInfoHelper.readDeployInfoProtoForTarget(
                  testComponents.targetApp,
                  buildResultHelper,
                  fileName -> fileName.endsWith(DEPLOY_INFO_FILE_SUFFIX));
          deployInfo =
              deployInfoHelper.extractInstrumentationTestDeployInfoAndInvalidateManifests(
                  project,
                  new File(executionRoot),
                  instrumentorDeployInfoProto,
                  targetDeployInfoProto);
        }
      } catch (GetArtifactsException e) {
        IssueOutput.error("Could not read BEP output: " + e.getMessage()).submit(context);
      } catch (GetDeployInfoException e) {
        IssueOutput.error("Could not read apk deploy info from build: " + e.getMessage())
            .submit(context);
      }
    }
  }

  @Override
  public BlazeAndroidDeployInfo getDeployInfo() throws ApkProvisionException {
    if (deployInfo != null) {
      return deployInfo;
    }
    throw new ApkProvisionException(
        "Failed to read APK deploy info.  Either build step hasn't been executed or there was an"
            + " error obtaining deploy info after build.");
  }

  /**
   * Extracts information about the test and target apps from the instrumentation test rule.
   *
   * @return The labels contained in an {@link InstrumentationInfo} object.
   */
  @Nullable
  @VisibleForTesting
  public static InstrumentationInfo getInstrumentationInfo(
      Label instrumentationTestLabel, BlazeProjectData projectData, BlazeContext context) {
    // The following extracts the dependency info required during an instrumentation test.
    // To disambiguate, we try to follow the same terminology as used by the
    // android_instrumentation_test rule docs:
    // - test: The android_instrumentation_test target.
    // - test_app: The target of kind android_binary that's used as the binary that
    // orchestrates the instrumentation test.
    // - target_app: The android_binary app that's being tested by the test_app.
    TargetMap targetMap = projectData.getTargetMap();
    TargetIdeInfo testTarget = targetMap.get(TargetKey.forPlainTarget(instrumentationTestLabel));
    if (testTarget == null
        || testTarget.getKind() != RuleTypes.ANDROID_INSTRUMENTATION_TEST.getKind()) {
      IssueOutput.error(
              "Unable to identify target \""
                  + instrumentationTestLabel
                  + "\". Please sync the project and try again.")
          .submit(context);
      return null;
    }
    AndroidInstrumentationInfo testInstrumentationInfo = testTarget.getAndroidInstrumentationInfo();
    if (testInstrumentationInfo == null) {
      IssueOutput.error(
              "Required target data missing for \""
                  + instrumentationTestLabel
                  + "\".  Has the target definition changed recently? Please sync the project and"
                  + " try again.")
          .submit(context);
      return null;
    }

    Label testApp = testInstrumentationInfo.getTestApp();
    if (testApp == null) {
      IssueOutput.error(
              "No \"test_app\" in target definition for "
                  + testTarget.getKey().getLabel()
                  + ". Please ensure \"test_app\" attribute is set.  See"
                  + " https://docs.bazel.build/versions/master/be/android.html#android_instrumentation_test.test_app"
                  + " for more information.")
          .submit(context);
      return null;
    }

    TargetIdeInfo testAppTargetIdeInfo = targetMap.get(TargetKey.forPlainTarget(testApp));
    if (testAppTargetIdeInfo == null) {
      IssueOutput.error(
              "Unable to identify target \""
                  + testApp
                  + "\". Please sync the project and try again.")
          .submit(context);
      return null;
    }
    AndroidIdeInfo testAppAndroidInfo = testAppTargetIdeInfo.getAndroidIdeInfo();
    if (testAppAndroidInfo == null) {
      IssueOutput.error(
              "Required target data missing for \""
                  + testApp
                  + "\".  Has the target definition changed recently? Please sync the project and"
                  + " try again.")
          .submit(context);
      return null;
    }
    Label targetApp = testAppAndroidInfo.getInstruments();
    return new InstrumentationInfo(targetApp, testApp);
  }

  /**
   * Container for information about {@code android_instrumentation_test}: it holds links to the
   * test target and the instrumented target.
   */
  @VisibleForTesting
  public static class InstrumentationInfo {
    /**
     * {@code android_binary} target corresponding to the application under test.
     *
     * <p>This is obtained from the {@code instruments} attribute of the binary that contains the
     * tests.
     */
    @Nullable public final Label targetApp;

    /**
     * {@code android_binary} target that contains the instrumentation tests.
     *
     * <p>This is obtained from the {@code test_app} attribute of an {@code
     * android_instrumentation_test} target.
     */
    public final Label testApp;

    InstrumentationInfo(@Nullable Label targetApp, Label testApp) {
      this.targetApp = targetApp;
      this.testApp = testApp;
    }

    /** Returns whether the test app contains the target itself (self-instrumenting). */
    public boolean isSelfInstrumentingTest() {
      return targetApp == null;
    }
  }
}
