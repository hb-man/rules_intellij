/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.sync.autosync;

import com.google.common.collect.ImmutableMap;
import com.google.idea.blaze.base.logging.LoggedSettingsProvider;
import com.google.idea.blaze.base.settings.BlazeUserSettings;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;

/** User settings specific to auto-sync. */
@State(name = "AutoSyncSettings", storages = @Storage("blaze.user.settings.xml"))
public class AutoSyncSettings implements PersistentStateComponent<AutoSyncSettings> {

  public boolean migratedOldAutoSyncSettings = false;
  public boolean onlyAutoSyncWhenSyncingRemotely = true;
  public boolean autoSyncOnBuildChanges = false;
  public boolean autoSyncOnProtoChanges = false;

  public static AutoSyncSettings getInstance() {
    return ServiceManager.getService(AutoSyncSettings.class);
  }

  @Override
  public AutoSyncSettings getState() {
    return this;
  }

  @Override
  public void loadState(AutoSyncSettings state) {
    XmlSerializerUtil.copyBean(state, this);

    // temporary migration code. Load order guaranteed by plugin xml.
    // TODO(brendandouglas): Remove migration code in August 2019
    BlazeUserSettings settings = BlazeUserSettings.getInstance();
    migrateOldSettings(settings.getResyncAutomatically(), settings.getResyncOnProtoChanges());
  }

  private void migrateOldSettings(boolean autoSyncOnBuildChanges, boolean autoSyncOnProtoChanges) {
    if (migratedOldAutoSyncSettings) {
      return;
    }
    this.migratedOldAutoSyncSettings = true;
    this.autoSyncOnBuildChanges = autoSyncOnBuildChanges;
    this.autoSyncOnProtoChanges = autoSyncOnProtoChanges;
  }

  static class SettingsLogger implements LoggedSettingsProvider {
    @Override
    public String getNamespace() {
      return "AutoSyncSettings";
    }

    @Override
    public ImmutableMap<String, String> getApplicationSettings() {
      AutoSyncSettings settings = AutoSyncSettings.getInstance();

      return ImmutableMap.<String, String>builder()
          .put(
              "onlyAutoSyncWhenSyncingRemotely",
              Boolean.toString(settings.onlyAutoSyncWhenSyncingRemotely))
          .put("autoSyncOnBuildChanges", Boolean.toString(settings.autoSyncOnBuildChanges))
          .put("autoSyncOnProtoChanges", Boolean.toString(settings.autoSyncOnProtoChanges))
          .build();
    }
  }
}
