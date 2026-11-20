package com.tom.rv2ide.utils;

import static org.junit.Assert.assertSame;

import com.tom.rv2ide.ui.themes.IDETheme;
import org.junit.Test;

public class IDEThemeTest {
  @Test
  public void restoresEveryExistingStoredThemeName() {
    for (IDETheme theme : IDETheme.values()) {
      assertSame(theme, IDETheme.fromStoredName(theme.name()));
    }
  }

  @Test
  public void missingOrUnsupportedPreferencesUseSunnyGlow() {
    assertSame(IDETheme.SUNNY_GLOW, IDETheme.fromStoredName(null));
    assertSame(IDETheme.SUNNY_GLOW, IDETheme.fromStoredName(""));
    assertSame(IDETheme.SUNNY_GLOW, IDETheme.fromStoredName("FUTURE_THEME"));
    assertSame(IDETheme.SUNNY_GLOW, IDETheme.fromStoredName("sunny_glow"));
  }
}
