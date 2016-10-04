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
package com.google.idea.blaze.ijwb.android;

import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.model.primitives.WorkspaceType;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import java.util.Set;

/** Rudimentary support for android in IntelliJ. */
public class BlazeAndroidLiteSyncPlugin extends BlazeSyncPlugin.Adapter {
  @Override
  public Set<LanguageClass> getSupportedLanguagesInWorkspace(WorkspaceType workspaceType) {
    switch (workspaceType) {
      case ANDROID:
      case JAVA:
        return ImmutableSet.of(LanguageClass.ANDROID);
      default:
        return ImmutableSet.of();
    }
  }
}
