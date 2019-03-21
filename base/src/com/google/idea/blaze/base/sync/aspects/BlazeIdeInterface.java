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
package com.google.idea.blaze.base.sync.aspects;

import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.command.buildresult.RemoteOutputArtifact;
import com.google.idea.blaze.base.command.info.BlazeConfigurationHandler;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.BlazeVersionData;
import com.google.idea.blaze.base.model.SyncState;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.blaze.base.sync.sharding.ShardedTargetList;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import java.util.Collection;
import javax.annotation.Nullable;

/** Indirection between ide_build_info and aspect style IDE info. */
public interface BlazeIdeInterface {

  static BlazeIdeInterface getInstance() {
    return ServiceManager.getService(BlazeIdeInterface.class);
  }

  /** The result of the ide-info build step. */
  class BuildResultIdeInfo {
    @Nullable public final TargetMap targetMap;
    public final ImmutableSet<RemoteOutputArtifact> remoteOutputs;
    public final BuildResult buildResult;

    public BuildResultIdeInfo(
        @Nullable TargetMap targetMap,
        ImmutableSet<RemoteOutputArtifact> remoteOutputs,
        BuildResult buildResult) {
      this.targetMap = targetMap;
      this.remoteOutputs = remoteOutputs;
      this.buildResult = buildResult;
    }
  }

  /** The result of the ide-resolve build step. */
  class BuildResultIdeResolve {
    public final Collection<RemoteOutputArtifact> remoteOutputs;
    public final BuildResult buildResult;

    public BuildResultIdeResolve(
        Collection<RemoteOutputArtifact> remoteOutputs, BuildResult buildResult) {
      this.remoteOutputs = remoteOutputs;
      this.buildResult = buildResult;
    }
  }

  /**
   * Queries blaze to update the rule map for the given targets.
   *
   * @param mergeWithOldState If true, we overlay the given targets to the current rule map.
   * @return A tuple of the latest updated rule map and the result of the operation.
   */
  BuildResultIdeInfo updateTargetMap(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeInfo blazeInfo,
      BlazeVersionData blazeVersionData,
      BlazeConfigurationHandler configHandler,
      ShardedTargetList shardedTargets,
      WorkspaceLanguageSettings workspaceLanguageSettings,
      SyncState.Builder syncStateBuilder,
      @Nullable SyncState previousSyncState,
      boolean mergeWithOldState,
      @Nullable TargetMap oldTargetMap);

  /**
   * Attempts to resolve the requested ide artifacts.
   *
   * <p>Amounts to a build of the ide-resolve output group.
   */
  BuildResultIdeResolve resolveIdeArtifacts(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeInfo blazeInfo,
      BlazeVersionData blazeVersionData,
      WorkspaceLanguageSettings workspaceLanguageSettings,
      ShardedTargetList shardedTargets);

  /**
   * Attempts to compile the requested ide artifacts.
   *
   * <p>Amounts to a build of the ide-compile output group.
   */
  BuildResult compileIdeArtifacts(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      BlazeVersionData blazeVersionData,
      WorkspaceLanguageSettings workspaceLanguageSettings,
      ShardedTargetList shardedTargets);
}
