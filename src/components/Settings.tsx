import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { useEffect, useRef, useState } from "react";
import {
  AppSettings,
  ClickThroughModifier,
  DEFAULT_SETTINGS,
  DexcomRegion,
  GraphStyle,
  GlucoseUnit,
  WidgetSize,
  WidgetTheme,
} from "../types";

export function Settings() {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS);
  const [draftRegion, setDraftRegion] = useState<DexcomRegion>(DEFAULT_SETTINGS.region);
  const [draftUsername, setDraftUsername] = useState(DEFAULT_SETTINGS.username);
  const [draftPassword, setDraftPassword] = useState(DEFAULT_SETTINGS.password);
  const [statusMsg, setStatusMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const [testing, setTesting] = useState(false);
  const [portableMode, setPortableMode] = useState(false);
  const saveTimerRef = useRef<number | null>(null);

  const isSignedIn =
    settings.username.trim().length > 0 && settings.password.length > 0;

  useEffect(() => {
    document.documentElement.classList.add("settings-mode");
    let unlisten: (() => void) | undefined;

    invoke<boolean>("is_portable_mode")
      .then((flag) => setPortableMode(flag))
      .catch(() => { });

    invoke<AppSettings>("get_settings")
      .then((loaded) => {
        setSettings(loaded);
        setDraftRegion(loaded.region);
        setDraftUsername(loaded.username);
        setDraftPassword(loaded.password);
      })
      .catch(() => { });

    listen<AppSettings>("settings-updated", (event) => {
      setSettings(event.payload);
      if (!event.payload.username && !event.payload.password) {
        setDraftUsername("");
        setDraftPassword("");
      }
    }).then((fn) => {
      unlisten = fn;
    });

    return () => {
      if (unlisten) unlisten();
      if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current);
    };
  }, []);

  const persistNow = (next: AppSettings) => {
    void invoke("save_settings", { settings: next }).catch((err) => {
      setStatusMsg({ kind: "err", text: String(err) });
    });
  };

  const updateImmediate = <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => {
    const next = { ...settings, [key]: value };
    setSettings(next);
    persistNow(next);
  };

  const updateDebounced = <K extends keyof AppSettings>(key: K, value: AppSettings[K]) => {
    const next = { ...settings, [key]: value };
    setSettings(next);
    if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current);
    saveTimerRef.current = window.setTimeout(() => {
      persistNow(next);
    }, 180);
  };

  const handleSignInAndConnect = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const cleanUser = draftUsername.trim();
    if (!cleanUser || !draftPassword) {
      setStatusMsg({
        kind: "err",
        text: "Enter your primary Dexcom username and password.",
      });
      return;
    }

    setTesting(true);
    setStatusMsg(null);
    try {
      const msg = await invoke<string>("test_dexcom_connection", {
        region: draftRegion,
        username: cleanUser,
        password: draftPassword,
      });
      const next: AppSettings = {
        ...settings,
        region: draftRegion,
        username: cleanUser,
        password: draftPassword,
      };
      setSettings(next);
      await invoke("save_settings", { settings: next });
      setStatusMsg({ kind: "ok", text: `Signed in! ${msg}` });
    } catch (err) {
      setStatusMsg({ kind: "err", text: typeof err === "string" ? err : String(err) });
    } finally {
      setTesting(false);
    }
  };

  const handleSignOut = async () => {
    const next: AppSettings = {
      ...settings,
      username: "",
      password: "",
      clickThrough: false,
    };
    setSettings(next);
    setDraftUsername("");
    setDraftPassword("");
    await invoke("save_settings", { settings: next }).catch(() => { });
    setStatusMsg({ kind: "ok", text: "Signed out." });
  };

  return (
    <div className="settings-page">
      <header className="settings-header">
        <div>
          <div className="header-title-row">
            <h1>AlGlucoMon</h1>
            {portableMode && <span className="portable-badge">Portable</span>}
          </div>
          <p>by Almoukhtar Amiouni — live Dexcom G7 desktop overlay</p>
        </div>
      </header>

      <section className="settings-card">
        <h2>Dexcom G7 Sign-In</h2>

        {isSignedIn && (
          <div className="signed-in-bar">
            <span className="signed-in-badge">
              <span className="signed-in-dot" />
              Signed in as <strong>{settings.username}</strong>
            </span>
            <button type="button" className="secondary-btn" onClick={handleSignOut}>
              Sign Out
            </button>
          </div>
        )}

        <form className="field-stack" onSubmit={handleSignInAndConnect}>
          <label className="field">
            <span>Region</span>
            <select
              value={draftRegion}
              onChange={(e) => setDraftRegion(e.target.value as DexcomRegion)}
            >
              <option value="us">United States (US)</option>
              <option value="ous">Outside US (OUS)</option>
              <option value="jp">Japan (JP)</option>
            </select>
          </label>

          <label className="field">
            <span>Primary Dexcom Username / Email</span>
            <input
              type="text"
              placeholder="Wearer's Dexcom login"
              autoComplete="username"
              value={draftUsername}
              onChange={(e) => setDraftUsername(e.target.value)}
            />
          </label>

          <label className="field">
            <span>Password</span>
            <input
              type="password"
              placeholder="Dexcom password"
              autoComplete="current-password"
              value={draftPassword}
              onChange={(e) => setDraftPassword(e.target.value)}
            />
          </label>

          <div className="actions-row">
            <button type="submit" className="primary-btn" disabled={testing}>
              {testing
                ? "Connecting..."
                : isSignedIn
                  ? "Update & Test Connection"
                  : "Sign In & Connect"}
            </button>
            {statusMsg && (
              <span className={`status-msg ${statusMsg.kind}`}>{statusMsg.text}</span>
            )}
          </div>
        </form>

        <label className="row">
          <span className="row-title">Refresh interval</span>
          <select
            value={settings.pollIntervalSec}
            onChange={(e) => updateImmediate("pollIntervalSec", Number(e.target.value))}
          >
            <option value={60}>Every 60 seconds</option>
            <option value={120}>Every 2 minutes</option>
            <option value={300}>Every 5 minutes</option>
          </select>
        </label>

        <p className="hint-note">
          Requires the wearer&apos;s primary Dexcom account (not a follower account), with{" "}
          <strong>Dexcom Share enabled</strong> and at least 1 active follower in the G7 phone app.
        </p>
      </section>

      <section className="settings-card">
        <h2>Background Trend Graph</h2>

        <label className="row toggle-row">
          <div>
            <span className="row-title">Show trend graph background</span>
            <span className="row-sub">
              Renders a soft full-bleed glucose curve behind your readout
            </span>
          </div>
          <input
            type="checkbox"
            checked={settings.showBackgroundGraph}
            onChange={(e) => updateImmediate("showBackgroundGraph", e.target.checked)}
          />
        </label>

        <div className={`sub-controls${settings.showBackgroundGraph ? "" : " disabled"}`}>
          <div className="row">
            <span className="row-title">Time window</span>
            <div className="segmented">
              {([1, 3, 6] as const).map((hrs) => (
                <button
                  key={hrs}
                  type="button"
                  className={settings.graphHours === hrs ? "active" : ""}
                  onClick={() => updateImmediate("graphHours", hrs)}
                >
                  {hrs}h
                </button>
              ))}
            </div>
          </div>

          <div className="row">
            <span className="row-title">Graph style</span>
            <div className="segmented">
              {(
                [
                  { id: "smooth", label: "Smooth curve" },
                  { id: "dots", label: "Dots" },
                ] as { id: GraphStyle; label: string }[]
              ).map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  className={settings.graphStyle === opt.id ? "active" : ""}
                  onClick={() => updateImmediate("graphStyle", opt.id)}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {settings.graphStyle === "dots" && (
            <span className="row-sub">
              Each dot represents a glucose reading — hover any dot on the overlay to see its value and time.
            </span>
          )}

          <label className="row">
            <span className="row-title">
              Graph opacity ({Math.round(settings.graphOpacity * 100)}%)
            </span>
            <input
              type="range"
              min={0.15}
              max={0.85}
              step={0.05}
              value={settings.graphOpacity}
              onChange={(e) => updateDebounced("graphOpacity", parseFloat(e.target.value))}
            />
          </label>

          <label className="row toggle-row">
            <span className="row-title">Show target range band</span>
            <input
              type="checkbox"
              checked={settings.showTargetBand}
              onChange={(e) => updateImmediate("showTargetBand", e.target.checked)}
            />
          </label>
        </div>
      </section>

      <section className="settings-card">
        <h2>Appearance &amp; Overlay</h2>

        <div className="row">
          <span className="row-title">Theme</span>
          <select
            value={settings.theme}
            onChange={(e) => updateImmediate("theme", e.target.value as WidgetTheme)}
          >
            <option value="dark-glass">Dark Glass</option>
            <option value="adaptive">Adaptive Range Glow</option>
            <option value="light-glass">Light Glass</option>
            <option value="oled">OLED Black</option>
          </select>
        </div>

        <div className="row">
          <span className="row-title">Widget size</span>
          <div className="segmented">
            {(["compact", "standard", "large"] as WidgetSize[]).map((sz) => (
              <button
                key={sz}
                type="button"
                className={settings.widgetSize === sz ? "active" : ""}
                onClick={() => updateImmediate("widgetSize", sz)}
              >
                {sz[0].toUpperCase() + sz.slice(1)}
              </button>
            ))}
          </div>
        </div>

        <label className="row">
          <span className="row-title">
            Card opacity ({Math.round(settings.opacity * 100)}%)
          </span>
          <input
            type="range"
            min={0.3}
            max={1}
            step={0.02}
            value={settings.opacity}
            onChange={(e) => updateDebounced("opacity", parseFloat(e.target.value))}
          />
        </label>

        <div className="row">
          <span className="row-title">Units</span>
          <div className="segmented">
            {(["mg/dL", "mmol/L"] as GlucoseUnit[]).map((u) => (
              <button
                key={u}
                type="button"
                className={settings.unit === u ? "active" : ""}
                onClick={() => updateImmediate("unit", u)}
              >
                {u}
              </button>
            ))}
          </div>
        </div>

        <div className="thresholds-grid">
          <label>
            <span>Urgent Low (mg/dL)</span>
            <input
              type="number"
              min={40}
              max={90}
              value={settings.urgentLowThreshold}
              onChange={(e) =>
                updateDebounced("urgentLowThreshold", Number(e.target.value) || 55)
              }
            />
          </label>
          <label>
            <span>Low (mg/dL)</span>
            <input
              type="number"
              min={50}
              max={120}
              value={settings.lowThreshold}
              onChange={(e) => updateDebounced("lowThreshold", Number(e.target.value) || 70)}
            />
          </label>
          <label>
            <span>High (mg/dL)</span>
            <input
              type="number"
              min={120}
              max={350}
              value={settings.highThreshold}
              onChange={(e) => updateDebounced("highThreshold", Number(e.target.value) || 180)}
            />
          </label>
        </div>

        <label className="row toggle-row">
          <span className="row-title">Show delta &amp; reading age</span>
          <input
            type="checkbox"
            checked={settings.showDelta}
            onChange={(e) => updateImmediate("showDelta", e.target.checked)}
          />
        </label>

        <label className="row toggle-row">
          <span className="row-title">Always on top</span>
          <input
            type="checkbox"
            checked={settings.alwaysOnTop}
            onChange={(e) => updateImmediate("alwaysOnTop", e.target.checked)}
          />
        </label>

        <label className={`row toggle-row${portableMode ? " disabled" : ""}`}>
          <div>
            <span className="row-title">Launch on Windows startup</span>
            <span className="row-sub">
              {portableMode
                ? "Disabled in portable mode to avoid writing Windows Registry entries."
                : "Automatically start AlGlucoMon when you sign in to Windows"}
            </span>
          </div>
          <input
            type="checkbox"
            disabled={portableMode}
            checked={portableMode ? false : settings.runOnStartup}
            onChange={(e) => updateImmediate("runOnStartup", e.target.checked)}
          />
        </label>

        <label className="row toggle-row">
          <div>
            <span className="row-title">Click-through lock</span>
            <span className="row-sub">
              Dims overlay &amp; passes clicks through. Hold{" "}
              {settings.clickThroughModifier === "ctrl"
                ? "Ctrl"
                : settings.clickThroughModifier === "shift"
                  ? "Shift"
                  : "Alt"}{" "}
              to hover dots, drag, or click lock to disable.
            </span>
          </div>
          <input
            type="checkbox"
            checked={settings.clickThrough}
            onChange={(e) => updateImmediate("clickThrough", e.target.checked)}
          />
        </label>

        <div className="sub-controls">
          <label className="row">
            <span className="row-title">
              Locked opacity ({Math.round(settings.clickThroughOpacity * 100)}%)
            </span>
            <input
              type="range"
              min={0.15}
              max={1}
              step={0.05}
              value={settings.clickThroughOpacity}
              onChange={(e) =>
                updateDebounced("clickThroughOpacity", parseFloat(e.target.value))
              }
            />
          </label>

          <div className="row">
            <span className="row-title">Hold key to interact / unlock</span>
            <div className="segmented">
              {(
                [
                  { id: "alt", label: "Alt" },
                  { id: "ctrl", label: "Ctrl" },
                  { id: "shift", label: "Shift" },
                ] as { id: ClickThroughModifier; label: string }[]
              ).map((mod) => (
                <button
                  key={mod.id}
                  type="button"
                  className={settings.clickThroughModifier === mod.id ? "active" : ""}
                  onClick={() => updateImmediate("clickThroughModifier", mod.id)}
                >
                  {mod.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
