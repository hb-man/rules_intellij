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
package com.google.idea.blaze.base.command;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.BlazeTestCase;
import com.google.idea.blaze.base.experiments.ExperimentService;
import com.google.idea.blaze.base.experiments.MockExperimentService;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.google.idea.blaze.base.settings.Blaze.BuildSystem;
import com.google.idea.blaze.base.settings.BlazeUserSettings;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Tests for {@link BlazeCommand}.
 */
@RunWith(JUnit4.class)
public class BlazeCommandTest extends BlazeTestCase {

  @Override
  protected void initTest(@NotNull Container applicationServices, @NotNull Container projectServices) {
    ExperimentService experimentService = new MockExperimentService();
    applicationServices.register(ExperimentService.class, experimentService);
    applicationServices.register(BlazeUserSettings.class, new BlazeUserSettings());
  }

  @Test
  public void addedFlagsShouldGoAtStart() {
    List<String> flagsCommand = BlazeCommand.builder(BuildSystem.Blaze, BlazeCommandName.RUN)
      .addTargets(new Label("//a:b"))
      .addBlazeFlags("--flag1", "--flag2")
      .addExeFlags("--exeFlag1", "--exeFlag2")
      .build()
      .toList();
    // First three strings are always 'blaze run --tool_tag=ijwb:IDEA:ultimate'
    assertThat(flagsCommand.subList(3, 5))
      .isEqualTo(ImmutableList.of("--flag1", "--flag2"));
  }

  @Test
  public void targetsShouldGoAfterBlazeFlagsAndDoubleHyphen() {
    List<String> command = BlazeCommand.builder(BuildSystem.Blaze, BlazeCommandName.RUN)
      .addTargets(new Label("//a:b"), new Label("//c:d"))
      .addBlazeFlags("--flag1", "--flag2")
      .addExeFlags("--exeFlag1", "--exeFlag2")
      .build()
      .toList();
    // First six strings should be 'blaze run --tool_tag=ijwb:IDEA:ultimate --flag1 --flag2 --'
    assertThat(command.indexOf("--")).isEqualTo(5);
    assertThat(Collections.indexOfSubList(command, ImmutableList.of("//a:b", "//c:d"))).isEqualTo(6);
  }

  @Test
  public void exeFlagsShouldGoLast() {
    List<String> command = BlazeCommand.builder(BuildSystem.Blaze, BlazeCommandName.RUN)
      .addTargets(new Label("//a:b"), new Label("//c:d"))
      .addBlazeFlags("--flag1", "--flag2")
      .addExeFlags("--exeFlag1", "--exeFlag2")
      .build()
      .toList();
    List<String> finalTwoFlags = command.subList(command.size() - 2, command.size());
    assertThat(finalTwoFlags).containsExactly("--exeFlag1", "--exeFlag2");
  }

  @Test
  public void maintainUserOrderingOfTargets() {
    List<String> command = BlazeCommand.builder(BuildSystem.Blaze, BlazeCommandName.RUN)
      .addTargets(new Label("//a:b"), TargetExpression.fromString("-//e:f"), new Label("//c:d"))
      .addBlazeFlags("--flag1", "--flag2")
      .addExeFlags("--exeFlag1", "--exeFlag2")
      .build()
      .toList();

    ImmutableList<Object> expected = ImmutableList.builder()
      .add("/usr/bin/blaze")
      .add("run")
      .add(BlazeFlags.getToolTagFlag())
      .add("--flag1")
      .add("--flag2")
      .add("--")
      .add("//a:b")
      .add("-//e:f")
      .add("//c:d")
      .add("--exeFlag1")
      .add("--exeFlag2")
      .build();
    assertThat(command).isEqualTo(expected);
  }

  @Test
  public void binaryAndCommandShouldComeFirst() {
    List<String> command = BlazeCommand.builder(BuildSystem.Blaze, BlazeCommandName.BUILD)
      .addBlazeFlags("--flag")
      .addExeFlags("--exeFlag")
      .build()
      .toList();
    assertThat(command.subList(0, 2)).isEqualTo(ImmutableList.of("/usr/bin/blaze", "build"));
  }
}
