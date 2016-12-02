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
package com.google.idea.blaze.cpp;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.idea.blaze.base.ideinfo.CIdeInfo;
import com.google.idea.blaze.base.ideinfo.CToolchainIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.primitives.ExecutionRootPath;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Scope;
import com.google.idea.blaze.base.scope.scopes.TimingScope;
import com.google.idea.blaze.base.sync.workspace.WorkspacePathResolver;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.cidr.lang.OCFileTypeHelpers;
import com.jetbrains.cidr.lang.OCLanguageKind;
import com.jetbrains.cidr.lang.preprocessor.OCImportGraph;
import com.jetbrains.cidr.lang.workspace.OCLanguageKindCalculator;
import com.jetbrains.cidr.lang.workspace.OCResolveConfiguration;
import com.jetbrains.cidr.lang.workspace.OCResolveRootAndConfiguration;
import com.jetbrains.cidr.lang.workspace.OCWorkspaceUtil;
import com.jetbrains.cidr.lang.workspace.compiler.CidrCompilerResult;
import com.jetbrains.cidr.lang.workspace.compiler.OCCompilerMacros;
import com.jetbrains.cidr.lang.workspace.compiler.OCCompilerSettings;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeaderRoots;
import com.jetbrains.cidr.lang.workspace.headerRoots.HeadersSearchRoot;
import com.jetbrains.cidr.lang.workspace.headerRoots.IncludedHeadersRoot;
import com.jetbrains.cidr.toolchains.CompilerInfoCache;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * This is a temporary base class to deal with API changes between v145 (Android Studio) and v162
 * (CLion). Once Android Studio's API has caught up, the features in versioned/v162 can be merged,
 * this class be renamed BlazeResolveConfiguration, and it can be made final.
 */
abstract class BlazeResolveConfigurationTemporaryBase extends UserDataHolderBase
    implements OCResolveConfiguration {

  public static final Logger LOG = Logger.getInstance(BlazeResolveConfiguration.class);

  private final WorkspacePathResolver workspacePathResolver;

  /* project, label are protected instead of private just so v145 can access */
  protected final Project project;
  protected final TargetKey targetKey;

  private final ImmutableList<HeadersSearchRoot> cLibraryIncludeRoots;
  private final ImmutableList<HeadersSearchRoot> cppLibraryIncludeRoots;
  private final HeaderRoots projectIncludeRoots;

  private final CompilerInfoCache compilerInfoCache;
  private final BlazeCompilerMacros compilerMacros;
  private final BlazeCompilerSettings compilerSettings;

  @Nullable
  public static BlazeResolveConfiguration createConfigurationForTarget(
      Project project,
      WorkspacePathResolver workspacePathResolver,
      ImmutableMap<File, VirtualFile> headerRoots,
      TargetIdeInfo target,
      CToolchainIdeInfo toolchainIdeInfo,
      File compilerWrapper) {
    CIdeInfo cIdeInfo = target.cIdeInfo;
    if (cIdeInfo == null) {
      return null;
    }

    ImmutableSet.Builder<ExecutionRootPath> systemIncludesBuilder = ImmutableSet.builder();
    systemIncludesBuilder.addAll(cIdeInfo.transitiveSystemIncludeDirectories);
    systemIncludesBuilder.addAll(toolchainIdeInfo.builtInIncludeDirectories);
    systemIncludesBuilder.addAll(toolchainIdeInfo.unfilteredToolchainSystemIncludes);

    ImmutableSet.Builder<ExecutionRootPath> userIncludesBuilder = ImmutableSet.builder();
    userIncludesBuilder.addAll(cIdeInfo.transitiveIncludeDirectories);

    ImmutableSet.Builder<ExecutionRootPath> userQuoteIncludesBuilder = ImmutableSet.builder();
    userQuoteIncludesBuilder.addAll(cIdeInfo.transitiveQuoteIncludeDirectories);

    ImmutableList.Builder<String> cFlagsBuilder = ImmutableList.builder();
    cFlagsBuilder.addAll(toolchainIdeInfo.baseCompilerOptions);
    cFlagsBuilder.addAll(toolchainIdeInfo.cCompilerOptions);
    cFlagsBuilder.addAll(toolchainIdeInfo.unfilteredCompilerOptions);

    ImmutableList.Builder<String> cppFlagsBuilder = ImmutableList.builder();
    cppFlagsBuilder.addAll(toolchainIdeInfo.baseCompilerOptions);
    cppFlagsBuilder.addAll(toolchainIdeInfo.cppCompilerOptions);
    cppFlagsBuilder.addAll(toolchainIdeInfo.unfilteredCompilerOptions);

    ImmutableMap<String, String> features = ImmutableMap.of();

    return new BlazeResolveConfiguration(
        project,
        workspacePathResolver,
        headerRoots,
        target.key,
        systemIncludesBuilder.build(),
        systemIncludesBuilder.build(),
        userQuoteIncludesBuilder.build(),
        userIncludesBuilder.build(),
        userIncludesBuilder.build(),
        cIdeInfo.transitiveDefines,
        features,
        compilerWrapper,
        compilerWrapper,
        cFlagsBuilder.build(),
        cppFlagsBuilder.build());
  }

  public static ImmutableMap<TargetKey, CToolchainIdeInfo> buildToolchainLookupMap(
      BlazeContext context,
      TargetMap targetMap,
      ImmutableMultimap<TargetKey, TargetKey> reverseDependencies) {
    return Scope.push(
        context,
        childContext -> {
          childContext.push(new TimingScope("Build toolchain lookup map"));

          List<TargetKey> seeds = Lists.newArrayList();
          for (TargetIdeInfo target : targetMap.targets()) {
            CToolchainIdeInfo cToolchainIdeInfo = target.cToolchainIdeInfo;
            if (cToolchainIdeInfo != null) {
              seeds.add(target.key);
            }
          }

          Map<TargetKey, CToolchainIdeInfo> lookupTable = Maps.newHashMap();
          for (TargetKey seed : seeds) {
            CToolchainIdeInfo toolchainInfo = targetMap.get(seed).cToolchainIdeInfo;
            LOG.assertTrue(toolchainInfo != null);
            List<TargetKey> worklist = Lists.newArrayList(reverseDependencies.get(seed));
            while (!worklist.isEmpty()) {
              // We should never see a label depend on two different toolchains.
              TargetKey l = worklist.remove(0);
              CToolchainIdeInfo previousValue = lookupTable.putIfAbsent(l, toolchainInfo);
              // Don't propagate the toolchain twice.
              if (previousValue == null) {
                worklist.addAll(reverseDependencies.get(l));
              } else {
                LOG.assertTrue(previousValue.equals(toolchainInfo));
              }
            }
          }
          return ImmutableMap.copyOf(lookupTable);
        });
  }

  public BlazeResolveConfigurationTemporaryBase(
      Project project,
      WorkspacePathResolver workspacePathResolver,
      ImmutableMap<File, VirtualFile> headerRoots,
      TargetKey targetKey,
      ImmutableCollection<ExecutionRootPath> cSystemIncludeDirs,
      ImmutableCollection<ExecutionRootPath> cppSystemIncludeDirs,
      ImmutableCollection<ExecutionRootPath> quoteIncludeDirs,
      ImmutableCollection<ExecutionRootPath> cIncludeDirs,
      ImmutableCollection<ExecutionRootPath> cppIncludeDirs,
      ImmutableCollection<String> defines,
      ImmutableMap<String, String> features,
      File cCompilerExecutable,
      File cppCompilerExecutable,
      ImmutableList<String> cCompilerFlags,
      ImmutableList<String> cppCompilerFlags) {
    this.workspacePathResolver = workspacePathResolver;
    this.project = project;
    this.targetKey = targetKey;

    ImmutableList.Builder<HeadersSearchRoot> cIncludeRootsBuilder = ImmutableList.builder();
    collectHeaderRoots(headerRoots, cIncludeRootsBuilder, cIncludeDirs, true /* isUserHeader */);
    collectHeaderRoots(
        headerRoots, cIncludeRootsBuilder, cSystemIncludeDirs, false /* isUserHeader */);
    this.cLibraryIncludeRoots = cIncludeRootsBuilder.build();

    ImmutableList.Builder<HeadersSearchRoot> cppIncludeRootsBuilder = ImmutableList.builder();
    collectHeaderRoots(
        headerRoots, cppIncludeRootsBuilder, cppIncludeDirs, true /* isUserHeader */);
    collectHeaderRoots(
        headerRoots, cppIncludeRootsBuilder, cppSystemIncludeDirs, false /* isUserHeader */);
    this.cppLibraryIncludeRoots = cppIncludeRootsBuilder.build();

    ImmutableList.Builder<HeadersSearchRoot> quoteIncludeRootsBuilder = ImmutableList.builder();
    collectHeaderRoots(
        headerRoots, quoteIncludeRootsBuilder, quoteIncludeDirs, true /* isUserHeader */);
    this.projectIncludeRoots = new HeaderRoots(quoteIncludeRootsBuilder.build());

    this.compilerSettings =
        new BlazeCompilerSettings(
            project, cCompilerExecutable, cppCompilerExecutable, cCompilerFlags, cppCompilerFlags);

    this.compilerInfoCache = new CompilerInfoCache();
    this.compilerMacros =
        new BlazeCompilerMacros(project, compilerInfoCache, compilerSettings, defines, features);
  }

  @Override
  public Project getProject() {
    return project;
  }

  public WorkspacePathResolver getWorkspacePathResolver() {
    return workspacePathResolver;
  }

  @Override
  public String getDisplayName(boolean shorten) {
    return targetKey.toString();
  }

  @Nullable
  @Override
  public VirtualFile getPrecompiledHeader() {
    return null;
  }

  @Nullable
  @Override
  public OCLanguageKind getDeclaredLanguageKind(VirtualFile sourceOrHeaderFile) {
    String fileName = sourceOrHeaderFile.getName();
    if (OCFileTypeHelpers.isSourceFile(fileName)) {
      return getLanguageKind(sourceOrHeaderFile);
    }

    if (OCFileTypeHelpers.isHeaderFile(fileName)) {
      return getLanguageKind(getSourceFileForHeaderFile(sourceOrHeaderFile));
    }

    return null;
  }

  private OCLanguageKind getLanguageKind(@Nullable VirtualFile sourceFile) {
    OCLanguageKind kind = OCLanguageKindCalculator.tryFileTypeAndExtension(project, sourceFile);
    return kind != null ? kind : getMaximumLanguageKind();
  }

  @Nullable
  private VirtualFile getSourceFileForHeaderFile(VirtualFile headerFile) {
    ArrayList<VirtualFile> roots =
        new ArrayList<>(OCImportGraph.getAllHeaderRoots(project, headerFile));

    final String headerNameWithoutExtension = headerFile.getNameWithoutExtension();
    for (VirtualFile root : roots) {
      if (root.getNameWithoutExtension().equals(headerNameWithoutExtension)) {
        return root;
      }
    }
    return null;
  }

  @Override
  public OCLanguageKind getPrecompiledLanguageKind() {
    return getMaximumLanguageKind();
  }

  @Override
  public OCLanguageKind getMaximumLanguageKind() {
    return OCLanguageKind.CPP;
  }

  @Override
  public HeaderRoots getProjectHeadersRoots() {
    return projectIncludeRoots;
  }

  @Override
  public HeaderRoots getLibraryHeadersRoots(OCResolveRootAndConfiguration headerContext) {
    OCLanguageKind languageKind = headerContext.getKind();
    VirtualFile sourceFile = headerContext.getRootFile();
    if (languageKind == null) {
      languageKind = getLanguageKind(sourceFile);
    }

    ImmutableSet.Builder<HeadersSearchRoot> roots = ImmutableSet.builder();
    if (languageKind == OCLanguageKind.C) {
      roots.addAll(cLibraryIncludeRoots);
    } else {
      roots.addAll(cppLibraryIncludeRoots);
    }

    CidrCompilerResult<CompilerInfoCache.Entry> compilerInfoCacheHolder =
        compilerInfoCache.getCompilerInfoCache(project, compilerSettings, languageKind, sourceFile);
    CompilerInfoCache.Entry compilerInfo = compilerInfoCacheHolder.getResult();
    if (compilerInfo != null) {
      roots.addAll(compilerInfo.headerSearchPaths);
    }
    return new HeaderRoots(roots.build().asList());
  }

  private void collectHeaderRoots(
      ImmutableMap<File, VirtualFile> virtualFileCache,
      ImmutableList.Builder<HeadersSearchRoot> roots,
      ImmutableCollection<ExecutionRootPath> paths,
      boolean isUserHeader) {
    for (ExecutionRootPath executionRootPath : paths) {
      ImmutableList<File> possibleDirectories =
          workspacePathResolver.resolveToIncludeDirectories(executionRootPath);
      for (File f : possibleDirectories) {
        VirtualFile vf = virtualFileCache.get(f);
        if (vf != null) {
          roots.add(new IncludedHeadersRoot(project, vf, false /* recursive */, isUserHeader));
        }
      }
    }
  }

  @Override
  public OCCompilerMacros getCompilerMacros() {
    return compilerMacros;
  }

  @Override
  public OCCompilerSettings getCompilerSettings() {
    return compilerSettings;
  }

  @Nullable
  @Override
  public Object getIndexingCluster() {
    return null;
  }

  @Override
  public int compareTo(OCResolveConfiguration other) {
    return OCWorkspaceUtil.compareConfigurations(this, other);
  }

  @Override
  public int hashCode() {
    // There should only be one configuration per target.
    return Objects.hash(targetKey);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof BlazeResolveConfiguration)) {
      return false;
    }

    BlazeResolveConfiguration that = (BlazeResolveConfiguration) obj;
    return compareTo(that) == 0;
  }
}
