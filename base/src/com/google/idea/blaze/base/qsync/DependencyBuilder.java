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
package com.google.idea.blaze.base.qsync;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.bazel.BuildSystem;
import com.google.idea.blaze.base.bazel.BuildSystem.BuildInvoker;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper.GetArtifactsException;
import com.google.idea.blaze.base.command.buildresult.OutputArtifact;
import com.google.idea.blaze.base.command.buildresult.ParsedBepOutput;
import com.google.idea.blaze.base.console.BlazeConsoleLineProcessorProvider;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.sync.aspects.strategy.AspectStrategy;
import com.google.idea.blaze.base.sync.projectview.ImportRoots;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/** An object that knows how to build dependencies for given targets */
public class DependencyBuilder {

  public DependencyBuilder() {}

  public ImmutableList<OutputArtifact> build(
      Project project,
      BlazeContext context,
      Set<String> buildTargets,
      ImportRoots ir,
      WorkspaceRoot workspaceRoot)
      throws IOException, GetArtifactsException {
    BuildSystem buildSystem = Blaze.getBuildSystemProvider(project).getBuildSystem();
    BuildInvoker invoker = buildSystem.getDefaultInvoker(project, context);
    try (BuildResultHelper buildResultHelper = invoker.createBuildResultHelper()) {

      IdeaPluginDescriptor plugin =
          PluginManager.getPlugin(
              PluginManager.getPluginByClassName(AspectStrategy.class.getName()));
      Path aspect = Paths.get(plugin.getPath().toString(), "aspect", "build_dependencies.bzl");
      String content = Files.readString(aspect);
      StringBuilder def = new StringBuilder("INCLUDE = [\n");
      for (WorkspacePath d : ir.rootDirectories()) {
        def.append("  \"//").append(d.toString()).append("\",\n");
      }
      def.append("]\n\nEXCLUDE = [\n");
      for (WorkspacePath d : ir.excludeDirectories()) {
        def.append("  \"//").append(d.toString()).append("\",\n");
      }
      def.append("]\n\n");
      content = content.replace("#insert __PROJECT__", def.toString());
      Files.writeString(workspaceRoot.directory().toPath().resolve(".aswb.bzl"), content);

      BlazeCommand builder =
          BlazeCommand.builder(invoker, BlazeCommandName.BUILD)
              .addBlazeFlags(buildTargets.toArray(new String[] {}))
              .addBlazeFlags(buildResultHelper.getBuildFlags())
              .addBlazeFlags(
                  "--aspects=//:.aswb.bzl%collect_dependencies,//:.aswb.bzl%package_dependencies")
              .addBlazeFlags("--output_groups=ij_query_sync")
              .addBlazeFlags("--noexperimental_run_validations")
              .build();

      LineProcessingOutputStream lpos =
          LineProcessingOutputStream.of(
              BlazeConsoleLineProcessorProvider.getAllStderrLineProcessors(context));
      ExternalTask.builder(workspaceRoot)
          .addBlazeCommand(builder)
          .context(context)
          .stdout(lpos)
          .stderr(lpos)
          .build()
          .run();
      ParsedBepOutput buildOutput = buildResultHelper.getBuildOutput();

      return buildOutput.getOutputGroupArtifacts("ij_query_sync", x -> true);
    }
  }
}
