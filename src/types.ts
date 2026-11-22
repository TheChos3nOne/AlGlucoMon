export type TrendDirection =
  | "None"
  | "DoubleUp"
  | "SingleUp"
  | "FortyFiveUp"
  | "Flat"
  | "FortyFiveDown"
  | "SingleDown"
  | "DoubleDown"
  | "NotComputable"
  | "RateOutOfRange"
  | "Unknown";

export type DexcomRegion = "us" | "ous" | "jp";
export type WidgetTheme = "dark-glass" | "adaptive" | "light-glass" | "oled";
export type WidgetSize = "compact" | "standard" | "large";
export type GraphStyle = "smooth" | "dots";
export type GlucoseUnit = "mg/dL" | "mmol/L";
export type GlucoseStatus = "urgent-low" | "low" | "in-range" | "high";
export type ClickThroughModifier = "alt" | "ctrl" | "shift";

export interface GlucoseReading {
  value: number;
  trend: TrendDirection;
  timestamp: number;
}

export interface AppSettings {
  region: DexcomRegion;
  username: string;
  password: string;
  pollIntervalSec: number;
  showBackgroundGraph: boolean;
  graphHours: 1 | 3 | 6;
  graphOpacity: number;
  showTargetBand: boolean;
  graphStyle: GraphStyle;
  unit: GlucoseUnit;
  urgentLowThreshold: number;
  lowThreshold: number;
  highThreshold: number;
  theme: WidgetTheme;
  opacity: number;
  widgetSize: WidgetSize;
  alwaysOnTop: boolean;
  runOnStartup: boolean;
  clickThrough: boolean;
  clickThroughOpacity: number;
  clickThroughModifier: ClickThroughModifier;
  showDelta: boolean;
}

export const DEFAULT_SETTINGS: AppSettings = {
  region: "us",
  username: "",
  password: "",
  pollIntervalSec: 120,
  showBackgroundGraph: true,
  graphHours: 3,
  graphOpacity: 0.45,
  showTargetBand: true,
  graphStyle: "dots",
  unit: "mg/dL",
  urgentLowThreshold: 55,
  lowThreshold: 70,
  highThreshold: 180,
  theme: "dark-glass",
  opacity: 0.86,
  widgetSize: "standard",
  alwaysOnTop: true,
  runOnStartup: false,
  clickThrough: false,
  clickThroughOpacity: 0.55,
  clickThroughModifier: "alt",
  showDelta: true,
};

export const WIDGET_DIMENSIONS: Record<WidgetSize, { width: number; height: number }> = {
  compact: { width: 196, height: 88 },
  standard: { width: 256, height: 116 },
  large: { width: 320, height: 144 },
};

const MMOL_FACTOR = 18.0182;

export function toDisplayNumber(mgDl: number, unit: GlucoseUnit): number {
  return unit === "mmol/L" ? mgDl / MMOL_FACTOR : mgDl;
}

export function formatGlucose(mgDl: number, unit: GlucoseUnit): string {
  if (unit === "mmol/L") {
    return (mgDl / MMOL_FACTOR).toFixed(1);
  }
  return Math.round(mgDl).toString();
}

export function formatDelta(deltaMgDl: number, unit: GlucoseUnit): string {
  if (unit === "mmol/L") {
    const val = deltaMgDl / MMOL_FACTOR;
    const rounded = Math.abs(val) < 0.05 ? 0 : val;
    const sign = rounded > 0 ? "+" : rounded < 0 ? "-" : "";
    return `${sign}${Math.abs(rounded).toFixed(1)}`;
  }
  const rounded = Math.round(deltaMgDl);
  const sign = rounded > 0 ? "+" : "";
  return `${sign}${rounded}`;
}

export function getGlucoseStatus(
  mgDl: number,
  settings: Pick<AppSettings, "urgentLowThreshold" | "lowThreshold" | "highThreshold">
): GlucoseStatus {
  if (mgDl <= settings.urgentLowThreshold) return "urgent-low";
  if (mgDl < settings.lowThreshold) return "low";
  if (mgDl > settings.highThreshold) return "high";
  return "in-range";
}

export function statusColor(status: GlucoseStatus): string {
  switch (status) {
    case "urgent-low":
      return "#ff4d6d";
    case "low":
      return "#fb923c";
    case "high":
      return "#facc15";
    case "in-range":
      return "#2dd4bf";
  }
}

export function formatAge(timestampMs: number, nowMs: number): { label: string; isStale: boolean } {
  const diffMs = Math.max(0, nowMs - timestampMs);
  const diffMin = Math.floor(diffMs / 60_000);
  const label =
    diffMin === 0 ? "now" : diffMin < 60 ? `${diffMin}m` : `${Math.floor(diffMin / 60)}h`;
  return { label, isStale: diffMs > 10 * 60_000 };
}
