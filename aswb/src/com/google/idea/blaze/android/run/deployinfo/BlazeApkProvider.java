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
package com.google.idea.blaze.android.run.deployinfo;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.ApkInfo;
import com.android.tools.idea.run.ApkProvider;
import com.android.tools.idea.run.ApkProvisionException;
import com.android.tools.idea.run.ValidationError;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.android.run.runner.AaptUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Apk provider from deploy info proto
 */
public class BlazeApkProvider implements ApkProvider {
  private final Project project;
  private final ListenableFuture<BlazeAndroidDeployInfo> deployInfoFuture;

  public BlazeApkProvider(Project project,
                          ListenableFuture<BlazeAndroidDeployInfo> deployInfoFuture) {
    this.project = project;
    this.deployInfoFuture = deployInfoFuture;
  }

  @NotNull
  @Override
  public Collection<ApkInfo> getApks(@NotNull IDevice device) throws ApkProvisionException {
    BlazeAndroidDeployInfo deployInfo = Futures.get(deployInfoFuture, ApkProvisionException.class);
    ImmutableList.Builder<ApkInfo> apkInfos = ImmutableList.builder();
    for (File apk : deployInfo.getApksToDeploy())  {
      apkInfos.add(new ApkInfo(apk, manifestPackageForApk(apk)));
    }
    return apkInfos.build();
  }

  @NotNull
  private String manifestPackageForApk(@NotNull final File apk) throws ApkProvisionException {
    try {
      return AaptUtil.getApkManifestPackage(project, apk);
    }
    catch (AaptUtil.AaptUtilException e) {
      throw new ApkProvisionException(
        "Could not determine manifest package for apk: " + apk.getPath()
        + "\nbecause: " + e.getMessage(),
        e);
    }
  }

  @NotNull
  @Override
  public List<ValidationError> validate() {
    return ImmutableList.of();
  }
}
