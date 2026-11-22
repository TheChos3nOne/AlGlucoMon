import { invoke } from "@tauri-apps/api/core";
import { LogicalSize } from "@tauri-apps/api/dpi";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { useEffect, useMemo, useState } from "react";
import {
  AppSettings,
  DEFAULT_SETTINGS,
  GlucoseReading,
  WIDGET_DIMENSIONS,
  formatAge,
  formatDelta,
  formatGlucose,
  getGlucoseStatus,
  statusColor,
} from "../types";
import { BackgroundGraph, VIEW_H, VIEW_W, computeGraphLayout } from "./BackgroundGraph";
import { TrendArrow } from "./TrendArrow";

const HOVER_RADIUS_SVG = 16;

export function Overlay() {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS);
  const [readings, setReadings] = useState<GlucoseReading[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState<number>(() => Date.now());
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);
  const [modifierBypass, setModifierBypass] = useState(false);

  const isSignedIn =
    settings.username.trim().length > 0 && settings.password.length > 0;

  useEffect(() => {
    document.documentElement.classList.remove("settings-mode");
    let unlistenSettings: (() => void) | undefined;
    let unlistenBypass: (() => void) | undefined;

    invoke<AppSettings>("get_settings")
      .then((loaded) => setSettings(loaded))
      .catch(() => {});

    listen<AppSettings>("settings-updated", (event) => {
      setSettings(event.payload);
      if (!event.payload.clickThrough) {
        setModifierBypass(false);
      }
    }).then((fn) => {
      unlistenSettings = fn;
    });

    listen<boolean>("modifier-override", (event) => {
      setModifierBypass(event.payload);
    }).then((fn) => {
      unlistenBypass = fn;
    });

    const preventAltMenu = (e: KeyboardEvent) => {
      if (e.key === "Alt") e.preventDefault();
    };
    window.addEventListener("keydown", preventAltMenu);

    return () => {
      if (unlistenSettings) unlistenSettings();
      if (unlistenBypass) unlistenBypass();
      window.removeEventListener("keydown", preventAltMenu);
    };
  }, []);

  useEffect(() => {
    const win = getCurrentWindow();
    const dims = WIDGET_DIMENSIONS[settings.widgetSize] ?? WIDGET_DIMENSIONS.standard;
    win.setSize(new LogicalSize(dims.width, dims.height)).catch(() => {});
    win.setAlwaysOnTop(settings.alwaysOnTop).catch(() => {});
  }, [settings.widgetSize, settings.alwaysOnTop]);

  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 15_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!isSignedIn) {
      setReadings([]);
      setErrorMsg(null);
      setIsLoading(false);
      return;
    }

    let cancelled = false;
    let initialLoad = true;
    const loadRealReadings = async () => {
      if (initialLoad) {
        setIsLoading(true);
      }
      try {
        const minutes = settings.graphHours * 60 + 30;
        const data = await invoke<GlucoseReading[]>("fetch_dexcom_readings", {
          minutes,
          maxCount: 90,
        });
        if (!cancelled) {
          setReadings(data);
          setErrorMsg(null);
          setNowMs(Date.now());
        }
      } catch (err) {
        if (!cancelled) {
          setErrorMsg(typeof err === "string" ? err : "Sync failed");
        }
      } finally {
        initialLoad = false;
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    };

    void loadRealReadings();
    const intervalMs = Math.max(30, settings.pollIntervalSec) * 1000;
    const id = window.setInterval(() => {
      void loadRealReadings();
    }, intervalMs);

    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [
    isSignedIn,
    settings.pollIntervalSec,
    settings.graphHours,
    settings.region,
    settings.username,
    settings.password,
  ]);

  const latest = readings.length > 0 ? readings[readings.length - 1] : null;
  const previous = readings.length > 1 ? readings[readings.length - 2] : null;

  const graphLayout = useMemo(
    () => computeGraphLayout(readings, settings),
    [readings, settings]
  );

  const isDotsMode =
    settings.showBackgroundGraph &&
    settings.graphStyle === "dots" &&
    graphLayout.points.length > 0;

  useEffect(() => {
    if (!isDotsMode) {
      setHoveredIndex(null);
    }
  }, [isDotsMode, readings.length]);

  const hoveredPoint =
    isDotsMode &&
    hoveredIndex !== null &&
    hoveredIndex >= 0 &&
    hoveredIndex < graphLayout.points.length
      ? graphLayout.points[hoveredIndex]
      : null;

  const status = useMemo(() => {
    if (!latest) return "in-range" as const;
    return getGlucoseStatus(latest.value, settings);
  }, [latest, settings]);

  const accent = statusColor(status);
  const isLightTheme = settings.theme === "light-glass";
  const age = latest ? formatAge(latest.timestamp, nowMs) : null;
  const deltaText =
    latest && previous ? formatDelta(latest.value - previous.value, settings.unit) : null;

  const arrowSize =
    settings.widgetSize === "compact" ? 22 : settings.widgetSize === "large" ? 34 : 28;

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!isDotsMode) return;
    if ((e.target as HTMLElement).closest("button")) {
      if (hoveredIndex !== null) setHoveredIndex(null);
      return;
    }

    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;

    const svgX = ((e.clientX - rect.left) / rect.width) * VIEW_W;
    const svgY = ((e.clientY - rect.top) / rect.height) * VIEW_H;

    let bestIdx = -1;
    let bestDist = HOVER_RADIUS_SVG;
    for (let i = 0; i < graphLayout.points.length; i++) {
      const pt = graphLayout.points[i];
      const dist = Math.hypot(pt.x - svgX, pt.y - svgY);
      if (dist <= bestDist) {
        bestDist = dist;
        bestIdx = i;
      }
    }

    const nextIdx = bestIdx >= 0 ? bestIdx : null;
    if (nextIdx !== hoveredIndex) {
      setHoveredIndex(nextIdx);
    }
  };

  const handleMouseLeave = () => {
    if (hoveredIndex !== null) setHoveredIndex(null);
  };

  const handleMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    if ((e.target as HTMLElement).closest("button")) return;
    void getCurrentWindow().startDragging();
  };

  const handleOpenSettings = () => {
    void invoke("open_settings");
  };

  const handleToggleLock = () => {
    if (!isSignedIn) return;
    const next: AppSettings = { ...settings, clickThrough: !settings.clickThrough };
    setSettings(next);
    void invoke("save_settings", { settings: next });
  };

  const cardBackground = (() => {
    const a = settings.opacity;
    switch (settings.theme) {
      case "light-glass":
        return `rgba(248, 250, 252, ${a})`;
      case "oled":
        return `rgba(4, 6, 10, ${Math.min(1, a + 0.08)})`;
      case "adaptive":
        return `linear-gradient(145deg, rgba(13, 18, 28, ${a}) 25%, ${accent}24 100%)`;
      case "dark-glass":
      default:
        return `rgba(13, 18, 28, ${a})`;
    }
  })();

  const cardBorder =
    settings.theme === "adaptive"
      ? `${accent}55`
      : isLightTheme
        ? "rgba(15, 23, 42, 0.14)"
        : "rgba(255, 255, 255, 0.13)";

  const tooltipInfo = useMemo(() => {
    if (!hoveredPoint) return null;
    const pctX = (hoveredPoint.x / VIEW_W) * 100;
    const pctY = (hoveredPoint.y / VIEW_H) * 100;
    const placeBelow = hoveredPoint.y < VIEW_H * 0.44;

    let left = `${pctX.toFixed(1)}%`;
    let tx = "-50%";
    if (pctX < 26) {
      left = `${Math.max(3, pctX).toFixed(1)}%`;
      tx = "0%";
    } else if (pctX > 74) {
      left = `${Math.min(97, pctX).toFixed(1)}%`;
      tx = "-100%";
    }

    const top = placeBelow
      ? `calc(${pctY.toFixed(1)}% + 10px)`
      : `calc(${pctY.toFixed(1)}% - 10px)`;
    const ty = placeBelow ? "0%" : "-100%";

    const timeStr = new Date(hoveredPoint.reading.timestamp).toLocaleTimeString([], {
      hour: "numeric",
      minute: "2-digit",
    });
    const pointAge = formatAge(hoveredPoint.reading.timestamp, nowMs);

    return {
      style: {
        left,
        top,
        transform: `translate(${tx}, ${ty})`,
      },
      valueText: formatGlucose(hoveredPoint.reading.value, settings.unit),
      timeStr,
      ageLabel: pointAge.label === "now" ? "now" : `${pointAge.label} ago`,
      color: hoveredPoint.color,
    };
  }, [hoveredPoint, nowMs, settings.unit]);

  const effectiveClickThrough = settings.clickThrough && isSignedIn && !modifierBypass;
  const isBypassLocked = settings.clickThrough && isSignedIn && modifierBypass;
  const modLabel =
    settings.clickThroughModifier === "ctrl"
      ? "Ctrl"
      : settings.clickThroughModifier === "shift"
        ? "Shift"
        : "Alt";

  return (
    <div
      className={`overlay-card size-${settings.widgetSize} theme-${settings.theme}${age?.isStale ? " is-stale" : ""}${hoveredPoint ? " is-inspecting" : ""}${effectiveClickThrough ? " is-click-through" : ""}${isBypassLocked ? " is-bypass-locked" : ""}`}
      style={{
        background: cardBackground,
        borderColor: cardBorder,
        opacity: effectiveClickThrough ? settings.clickThroughOpacity : 1,
      }}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseLeave={handleMouseLeave}
      onDoubleClick={(e) => {
        if (!(e.target as HTMLElement).closest("button")) {
          handleOpenSettings();
        }
      }}
    >
      <BackgroundGraph
        layout={graphLayout}
        settings={settings}
        accentColor={accent}
        isLightTheme={isLightTheme}
        hoveredIndex={hoveredIndex}
        isInspecting={hoveredPoint !== null}
      />

      {settings.showBackgroundGraph && readings.length >= 2 && (
        <div className="bg-scrim" aria-hidden="true" />
      )}

      <div className="overlay-top-bar">
        <span />
        <div className="hover-actions">
          {isSignedIn && (
            <button
              type="button"
              className={`icon-btn${settings.clickThrough ? " active-lock" : ""}`}
              onClick={handleToggleLock}
              title={
                settings.clickThrough
                  ? "Click to disable click-through lock"
                  : `Enable click-through lock (hold ${modLabel} to interact)`
              }
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none">
                <rect
                  x="5"
                  y="11"
                  width="14"
                  height="9"
                  rx="2"
                  stroke="currentColor"
                  strokeWidth="2"
                />
                {settings.clickThrough ? (
                  <path
                    d="M8 11V7a4 4 0 1 1 8 0v4"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                ) : (
                  <path
                    d="M8 11V7a4 4 0 0 1 7.6-1.7"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                )}
              </svg>
            </button>
          )}
          <button
            type="button"
            className="icon-btn"
            onClick={handleOpenSettings}
            title="Settings"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="2" />
              <path
                d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c.26.6.85 1 1.51 1H21a2 2 0 1 1 0 4h-.09c-.66 0-1.25.4-1.51 1Z"
                stroke="currentColor"
                strokeWidth="1.8"
              />
            </svg>
          </button>
        </div>
      </div>

      <div className="overlay-content">
        {latest ? (
          <>
            <div className="readout-row">
              <span
                className="glucose-value"
                style={{
                  color: age?.isStale ? undefined : accent,
                }}
              >
                {formatGlucose(latest.value, settings.unit)}
              </span>
              <TrendArrow trend={latest.trend} color={accent} size={arrowSize} />
            </div>

            {(settings.showDelta || errorMsg) && (
              <div className="meta-row">
                {errorMsg ? (
                  <span className="error-pill" title={errorMsg}>
                    {errorMsg.length > 26 ? `${errorMsg.slice(0, 26)}…` : errorMsg}
                  </span>
                ) : (
                  <>
                    {deltaText !== null && (
                      <span className="meta-delta">
                        {deltaText} <span className="meta-unit">{settings.unit}</span>
                      </span>
                    )}
                    {age && (
                      <span className={`meta-age${age.isStale ? " stale" : ""}`}>
                        • {age.label}
                      </span>
                    )}
                  </>
                )}
              </div>
            )}
          </>
        ) : !isSignedIn ? (
          <div className="empty-state">
            <div className="empty-header">
              <span className="pulse-badge" aria-hidden="true">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
                  <path
                    d="M3 12h4l2.5-6 4 12 2.5-6H21"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </span>
              <div className="empty-copy">
                <div className="empty-title">Sign in to Dexcom</div>
                <div className="empty-sub">Connect Dexcom G7 Share</div>
              </div>
            </div>
            <button type="button" className="empty-cta" onClick={handleOpenSettings}>
              Sign In
            </button>
          </div>
        ) : isLoading ? (
          <div className="empty-state">
            <div className="empty-header">
              <span className="status-dot connecting" aria-hidden="true" />
              <div className="empty-copy">
                <div className="empty-title">Connecting...</div>
                <div className="empty-sub">Dexcom G7 Share</div>
              </div>
            </div>
          </div>
        ) : errorMsg ? (
          <div className="empty-state">
            <div className="empty-header">
              <span className="status-dot error" aria-hidden="true" />
              <div className="empty-copy">
                <div className="empty-title">Connection issue</div>
                <div className="empty-sub" title={errorMsg}>
                  {errorMsg.length > 28 ? `${errorMsg.slice(0, 28)}…` : errorMsg}
                </div>
              </div>
            </div>
            <button
              type="button"
              className="empty-cta secondary"
              onClick={handleOpenSettings}
            >
              Open Settings
            </button>
          </div>
        ) : (
          <div className="empty-state">
            <div className="empty-header">
              <span className="status-dot waiting" aria-hidden="true" />
              <div className="empty-copy">
                <div className="empty-title">No readings yet</div>
                <div className="empty-sub">Waiting for Dexcom G7 Share</div>
              </div>
            </div>
          </div>
        )}
      </div>

      {tooltipInfo && (
        <div className="dot-tooltip" style={tooltipInfo.style}>
          <span
            className="dot-tooltip-swatch"
            style={{ background: tooltipInfo.color }}
          />
          <strong className="dot-tooltip-val">{tooltipInfo.valueText}</strong>
          <span className="dot-tooltip-unit">{settings.unit}</span>
          <span className="dot-tooltip-sep">•</span>
          <span className="dot-tooltip-time">{tooltipInfo.timeStr}</span>
          {settings.widgetSize !== "compact" && tooltipInfo.ageLabel !== "now" && (
            <span className="dot-tooltip-age">({tooltipInfo.ageLabel})</span>
          )}
        </div>
      )}
    </div>
  );
}
