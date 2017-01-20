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
package com.google.idea.blaze.android.settings;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.android.project.BlazeFeatureEnableService;
import com.google.idea.blaze.base.settings.ui.BlazeUserSettingsContributor;
import com.intellij.uiDesigner.core.GridConstraints;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Contributes Android-specific settings. */
public class BlazeAndroidUserSettingsContributor implements BlazeUserSettingsContributor {
  private final ImmutableList<JComponent> components;
  private JCheckBox useLayoutEditor;

  BlazeAndroidUserSettingsContributor() {
    useLayoutEditor = new JCheckBox();
    useLayoutEditor.setSelected(false);
    useLayoutEditor.setText("Use the layout editor for layout XML files. May freeze IDE.");

    ImmutableList.Builder<JComponent> builder = ImmutableList.builder();
    if (BlazeFeatureEnableService.isLayoutEditorExperimentEnabled()) {
      builder.add(useLayoutEditor);
    }
    components = builder.build();
  }

  @Override
  public void apply() {
    BlazeAndroidUserSettings settings = BlazeAndroidUserSettings.getInstance();
    settings.setUseLayoutEditor(useLayoutEditor.isSelected());
  }

  @Override
  public void reset() {
    BlazeAndroidUserSettings settings = BlazeAndroidUserSettings.getInstance();
    useLayoutEditor.setSelected(settings.getUseLayoutEditor());
  }

  @Override
  public boolean isModified() {
    BlazeAndroidUserSettings settings = BlazeAndroidUserSettings.getInstance();
    return !Objects.equal(useLayoutEditor.isSelected(), settings.getUseLayoutEditor());
  }

  @Override
  public int getRowCount() {
    return components.size();
  }

  @Override
  public int addComponents(JPanel panel, int rowi) {
    for (JComponent contributedComponent : components) {
      panel.add(
          contributedComponent,
          new GridConstraints(
              rowi++,
              0,
              1,
              2,
              GridConstraints.ANCHOR_NORTHWEST,
              GridConstraints.FILL_NONE,
              GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
              GridConstraints.SIZEPOLICY_FIXED,
              null,
              null,
              null,
              0,
              false));
    }
    return rowi;
  }

  static class BlazeAndroidUserSettingsProvider implements Provider {
    @Override
    public BlazeUserSettingsContributor getContributor() {
      return new BlazeAndroidUserSettingsContributor();
    }
  }
}
