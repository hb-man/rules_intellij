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
package com.google.idea.blaze.android.run.deployinfo;

import com.google.idea.blaze.android.manifest.ManifestParser;
import com.google.idea.blaze.android.manifest.ManifestParser.ParsedManifest;
import java.io.File;
import java.util.List;
import javax.annotation.Nullable;

/** Info about the deployment phase. */
public class BlazeAndroidDeployInfo {
  private final ParsedManifest mergedManifest;
  @Nullable private final ParsedManifest testTargetMergedManifest;
  private final List<File> apksToDeploy;

  /**
   * Note: Not every deployment has a test target, so {@param testTargetMergedManifest} can be null.
   */
  public BlazeAndroidDeployInfo(
      ParsedManifest mergedManifest,
      @Nullable ParsedManifest testTargetMergedManifest,
      List<File> apksToDeploy) {
    this.mergedManifest = mergedManifest;
    this.testTargetMergedManifest = testTargetMergedManifest;
    this.apksToDeploy = apksToDeploy;
  }

  /**
   * Returns parsed manifest of the main target for this deployment. During normal app deployment,
   * the main target is the android_binary that builds the app itself. During instrumentation tests
   * the main target is the android_binary/android_test target responsible for instrumenting the
   * app, while the merged manifest of the app under test can be obtained through {@link
   * BlazeAndroidDeployInfo#getTestTargetMergedManifest()}.
   */
  public ManifestParser.ParsedManifest getMergedManifest() {
    return mergedManifest;
  }

  /**
   * Returns parsed manifest of the app under test during an instrumentation test. This method
   * returns null in all other scenarios.
   */
  @Nullable
  public ManifestParser.ParsedManifest getTestTargetMergedManifest() {
    return testTargetMergedManifest;
  }

  /** Returns the full list of apks to deploy, if any. */
  List<File> getApksToDeploy() {
    return apksToDeploy;
  }
}
