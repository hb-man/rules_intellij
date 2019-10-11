/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android;

import static com.google.idea.blaze.android.targetmapbuilder.NbTargetBuilder.targetMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.idea.blaze.android.sdk.BlazeSdkProvider;
import com.google.idea.blaze.android.sdk.MockBlazeSdkProvider;
import com.google.idea.blaze.android.targetmapbuilder.NbTargetBuilder;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.settings.BuildSystem;
import com.google.idea.blaze.base.sync.BlazeSyncIntegrationTestCase;
import com.google.idea.blaze.base.sync.BlazeSyncParams;
import com.google.idea.blaze.base.sync.JdepsFileWriter;
import com.google.idea.blaze.base.sync.SyncMode;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkTypeId;
import org.junit.Rule;

/** Base class for integration tests that require an ASwB project setup. */
public class BlazeAndroidIntegrationTestCase extends BlazeSyncIntegrationTestCase {
  @Rule
  public final AndroidIntegrationTestSetupRule androidSetupRule =
      new AndroidIntegrationTestSetupRule();

  @Override
  protected final boolean isLightTestCase() {
    return false;
  }

  @Override
  protected final BuildSystem buildSystem() {
    return BuildSystem.Bazel;
  }

  public void setTargetMap(NbTargetBuilder... builders) {
    TargetMap targetMap = targetMap(builders);
    setTargetMap(targetMap);
    JdepsFileWriter.writeDefaultJdepsFiles(getExecRoot(), fileSystem, targetMap);
  }

  public static void mockSdk(String targetHash, String sdkName) {
    SdkTypeId sdkType = mock(SdkTypeId.class);
    when(sdkType.getName()).thenReturn("Android SDK");
    Sdk sdk = mock(Sdk.class);
    when(sdk.getName()).thenReturn(sdkName);
    when(sdk.getSdkType()).thenReturn(sdkType);
    MockBlazeSdkProvider sdkProvider = (MockBlazeSdkProvider) BlazeSdkProvider.getInstance();
    sdkProvider.addSdk(targetHash, sdk);
  }

  protected void runFullBlazeSync() {
    runBlazeSync(
        BlazeSyncParams.builder()
            .setTitle("full sync")
            .setSyncMode(SyncMode.FULL)
            .setAddProjectViewTargets(true)
            .build());
    errorCollector.assertNoIssues();
  }
}
