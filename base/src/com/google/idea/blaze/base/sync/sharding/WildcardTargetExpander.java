/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.sync.sharding;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.idea.blaze.base.async.FutureUtil;
import com.google.idea.blaze.base.async.process.ExternalTask;
import com.google.idea.blaze.base.async.process.LineProcessingOutputStream;
import com.google.idea.blaze.base.bazel.BuildSystemProvider;
import com.google.idea.blaze.base.command.BlazeCommand;
import com.google.idea.blaze.base.command.BlazeCommandName;
import com.google.idea.blaze.base.command.BlazeFlags;
import com.google.idea.blaze.base.command.BlazeInvocationContext;
import com.google.idea.blaze.base.console.BlazeConsoleLineProcessorProvider;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.model.primitives.WildcardTargetPattern;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.prefetch.PrefetchService;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.query.BlazeQueryLabelKindParser;
import com.google.idea.blaze.base.query.BlazeQueryLabelKindParser.RuleTypeAndLabel;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.output.StatusOutput;
import com.google.idea.blaze.base.scope.scopes.TimingScope;
import com.google.idea.blaze.base.scope.scopes.TimingScope.EventType;
import com.google.idea.blaze.base.settings.Blaze;
import com.google.idea.blaze.base.sync.aspects.BuildResult;
import com.google.idea.blaze.base.sync.aspects.BuildResult.Status;
import com.google.idea.blaze.base.sync.projectview.LanguageSupport;
import com.google.idea.blaze.base.sync.workspace.WorkspacePathResolver;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Expands wildcard target patterns into individual blaze targets. */
public class WildcardTargetExpander {

  private static final BoolExperiment filterByRuleType =
      new BoolExperiment("blaze.build.filter.by.rule.type", true);

  static class ExpandedTargetsResult {
    final List<TargetExpression> singleTargets;
    final BuildResult buildResult;

    ExpandedTargetsResult(List<TargetExpression> singleTargets, BuildResult buildResult) {
      this.singleTargets = singleTargets;
      this.buildResult = buildResult;
    }

    static ExpandedTargetsResult merge(ExpandedTargetsResult first, ExpandedTargetsResult second) {
      BuildResult buildResult = BuildResult.combine(first.buildResult, second.buildResult);
      List<TargetExpression> targets =
          ImmutableList.<TargetExpression>builder()
              .addAll(first.singleTargets)
              .addAll(second.singleTargets)
              .build();
      return new ExpandedTargetsResult(targets, buildResult);
    }
  }

  /**
   * Expand recursive wildcard blaze target patterns into single-package wildcard patterns, via a
   * file system traversal.
   *
   * <p>Exclude target patterns (beginning with '-') are not expanded.
   *
   * <p>Returns null if operation failed or was cancelled.
   */
  @Nullable
  static Map<TargetExpression, List<TargetExpression>> expandToNonRecursiveWildcardTargets(
      Project project,
      BlazeContext context,
      WorkspacePathResolver pathResolver,
      List<WildcardTargetPattern> wildcardPatterns) {

    Set<WildcardTargetPattern> excludes =
        wildcardPatterns.stream()
            .filter(WildcardTargetPattern::isExcluded)
            .collect(Collectors.toSet());

    Predicate<WorkspacePath> excludePredicate =
        workspacePath ->
            excludes.stream().anyMatch(pattern -> pattern.coversPackage(workspacePath));

    List<WildcardTargetPattern> includes = new ArrayList<>(wildcardPatterns);
    includes.removeAll(excludes);

    Set<File> toPrefetch =
        PackageLister.getDirectoriesToPrefetch(pathResolver, includes, excludePredicate);

    ListenableFuture<?> prefetchFuture =
        PrefetchService.getInstance().prefetchFiles(toPrefetch, false, false);
    if (!FutureUtil.waitForFuture(context, prefetchFuture)
        .withProgressMessage("Prefetching wildcard target pattern directories...")
        .timed("PrefetchingWildcardTargetDirectories", EventType.Prefetching)
        .onError("Prefetching wildcard target directories failed")
        .run()
        .success()) {
      return null;
    }

    return PackageLister.expandPackageTargets(
        Blaze.getBuildSystemProvider(project), context, pathResolver, includes);
  }

  /** Runs a sharded blaze query to expand wildcard targets to individual blaze targets */
  static ExpandedTargetsResult expandToSingleTargets(
      Project project,
      BlazeContext parentContext,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      List<TargetExpression> allTargets) {
    return Scope.push(
        parentContext,
        context -> {
          context.push(new TimingScope("ExpandTargetsQuery", EventType.BlazeInvocation));
          context.setPropagatesErrors(false);
          return doExpandToSingleTargets(
              project, context, workspaceRoot, projectViewSet, allTargets);
        });
  }

  private static ExpandedTargetsResult doExpandToSingleTargets(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      ProjectViewSet projectViewSet,
      List<TargetExpression> allTargets) {
    ImmutableList<ImmutableList<TargetExpression>> shards =
        shardTargets(allTargets, BlazeBuildTargetSharder.PACKAGE_SHARD_SIZE);
    Predicate<String> handledRulesPredicate = handledRuleTypes(projectViewSet);
    boolean excludeManualTargets = excludeManualTargets(project, projectViewSet);
    ExpandedTargetsResult output = null;
    for (int i = 0; i < shards.size(); i++) {
      List<TargetExpression> shard = shards.get(i);
      context.output(
          new StatusOutput(
              String.format(
                  "Expanding wildcard target patterns, shard %s of %s", i + 1, shards.size())));
      ExpandedTargetsResult result =
          queryIndividualTargets(
              project, context, workspaceRoot, handledRulesPredicate, shard, excludeManualTargets);
      output = output == null ? result : ExpandedTargetsResult.merge(output, result);
      if (output.buildResult.status == Status.FATAL_ERROR) {
        return output;
      }
    }
    return output;
  }

  /**
   * Partition targets list. Because order is important with respect to excluded targets, each shard
   * has all subsequent excluded targets appended to it.
   */
  @VisibleForTesting
  static ImmutableList<ImmutableList<TargetExpression>> shardTargets(
      List<TargetExpression> targets, int shardSize) {
    if (targets.size() <= shardSize) {
      return ImmutableList.of(ImmutableList.copyOf(targets));
    }
    List<ImmutableList<TargetExpression>> output = new ArrayList<>();
    for (int index = 0; index < targets.size(); index += shardSize) {
      int endIndex = Math.min(targets.size(), index + shardSize);
      List<TargetExpression> shard = new ArrayList<>(targets.subList(index, endIndex));
      if (shard.stream().filter(TargetExpression::isExcluded).count() == shard.size()) {
        continue;
      }
      List<TargetExpression> remainingExcludes =
          targets.subList(endIndex, targets.size()).stream()
              .filter(TargetExpression::isExcluded)
              .collect(Collectors.toList());
      shard.addAll(remainingExcludes);
      output.add(ImmutableList.copyOf(shard));
    }
    return ImmutableList.copyOf(output);
  }

  /**
   * A workaround to optionally allow manual targets if the user has specified the
   * '--build_manual_tests' flag in their .blazeproject file.
   *
   * <p>This is a gross hack, and only a partial workaround -- they could be using a different flag
   * format, have the flag in their .blazerc, etc.
   *
   * <p>Ideally '--build_manual_tests', which itself is a hacky workaround (applies only to wildcard
   * target pattern expansion) would work with blaze query.
   */
  private static boolean excludeManualTargets(Project project, ProjectViewSet projectView) {
    return !BlazeFlags.blazeFlags(
            project, projectView, BlazeCommandName.BUILD, BlazeInvocationContext.SYNC_CONTEXT)
        .contains("--build_manual_tests");
  }

  /** Runs a blaze query to expand the input target patterns to individual blaze targets. */
  private static ExpandedTargetsResult queryIndividualTargets(
      Project project,
      BlazeContext context,
      WorkspaceRoot workspaceRoot,
      Predicate<String> handledRulesPredicate,
      List<TargetExpression> targetPatterns,
      boolean excludeManualTargets) {
    String query = queryString(targetPatterns, excludeManualTargets);
    if (query.isEmpty()) {
      // will be empty if there are no non-excluded targets
      return new ExpandedTargetsResult(ImmutableList.of(), BuildResult.SUCCESS);
    }
    BlazeCommand.Builder builder =
        BlazeCommand.builder(getBinaryPath(project), BlazeCommandName.QUERY)
            .addBlazeFlags(BlazeFlags.KEEP_GOING)
            .addBlazeFlags("--output=label_kind")
            .addBlazeFlags(query);

    // it's fine to include wildcards here; they're guaranteed not to clash with actual labels.
    Set<String> explicitTargets =
        targetPatterns.stream().map(TargetExpression::toString).collect(Collectors.toSet());
    Predicate<RuleTypeAndLabel> filter =
        !filterByRuleType.getValue()
            ? t -> true
            : t -> handledRulesPredicate.test(t.ruleType) || explicitTargets.contains(t.label);

    BlazeQueryLabelKindParser outputProcessor = new BlazeQueryLabelKindParser(filter);

    int retVal =
        ExternalTask.builder(workspaceRoot)
            .addBlazeCommand(builder.build())
            .context(context)
            .stdout(LineProcessingOutputStream.of(outputProcessor))
            .stderr(
                LineProcessingOutputStream.of(
                    BlazeConsoleLineProcessorProvider.getAllStderrLineProcessors(context)))
            .build()
            .run();

    BuildResult buildResult = BuildResult.fromExitCode(retVal);
    return new ExpandedTargetsResult(outputProcessor.getTargetLabels(), buildResult);
  }

  private static Predicate<String> handledRuleTypes(ProjectViewSet projectViewSet) {
    return LanguageSupport.createWorkspaceLanguageSettings(projectViewSet)
        .getAvailableTargetKinds();
  }

  private static String queryString(List<TargetExpression> targets, boolean excludeManualTargets) {
    StringBuilder builder = new StringBuilder();
    for (TargetExpression target : targets) {
      boolean excluded = target.isExcluded();
      if (builder.length() == 0) {
        if (excluded) {
          continue; // an excluded target at the start of the list has no effect
        }
        builder.append("'").append(target).append("'");
      } else {
        if (excluded) {
          builder.append(" - ");
          // trim leading '-'
          String excludedTarget = target.toString();
          builder.append("'").append(excludedTarget, 1, excludedTarget.length()).append("'");
        } else {
          builder.append(" + ");
          builder.append("'").append(target).append("'");
        }
      }
    }
    String targetList = builder.toString();
    if (targetList.isEmpty()) {
      return targetList;
    }
    return excludeManualTargets
        ? String.format("attr('tags', '^((?!manual).)*$', %s)", targetList)
        : targetList;
  }

  private static String getBinaryPath(Project project) {
    BuildSystemProvider buildSystemProvider = Blaze.getBuildSystemProvider(project);
    return buildSystemProvider.getSyncBinaryPath(project);
  }
}
