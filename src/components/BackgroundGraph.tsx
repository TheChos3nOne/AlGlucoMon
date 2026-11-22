import { useId } from "react";
import { AppSettings, GlucoseReading, getGlucoseStatus, statusColor } from "../types";

export const VIEW_W = 260;
export const VIEW_H = 118;

export interface GraphPoint {
  x: number;
  y: number;
  reading: GlucoseReading;
  color: string;
}

export interface GraphLayout {
  points: GraphPoint[];
  linePath: string;
  areaPath: string;
  bandTop: number;
  bandBottom: number;
  bandHeight: number;
}

interface BackgroundGraphProps {
  layout: GraphLayout;
  settings: AppSettings;
  accentColor: string;
  isLightTheme: boolean;
  hoveredIndex?: number | null;
  isInspecting?: boolean;
}

function buildSmoothLinePath(points: { x: number; y: number }[]): string {
  if (points.length === 0) return "";
  if (points.length === 1) return `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`;
  if (points.length === 2) {
    return `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)} L ${points[1].x.toFixed(2)} ${points[1].y.toFixed(2)}`;
  }

  let d = `M ${points[0].x.toFixed(2)} ${points[0].y.toFixed(2)}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[Math.max(0, i - 1)];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[Math.min(points.length - 1, i + 2)];

    const cp1x = p1.x + (p2.x - p0.x) / 6;
    const cp1y = p1.y + (p2.y - p0.y) / 6;
    const cp2x = p2.x - (p3.x - p1.x) / 6;
    const cp2y = p2.y - (p3.y - p1.y) / 6;

    d += ` C ${cp1x.toFixed(2)} ${cp1y.toFixed(2)}, ${cp2x.toFixed(2)} ${cp2y.toFixed(2)}, ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`;
  }
  return d;
}

export function computeGraphLayout(
  readings: GlucoseReading[],
  settings: AppSettings
): GraphLayout {
  if (!settings.showBackgroundGraph || readings.length < 2) {
    return {
      points: [],
      linePath: "",
      areaPath: "",
      bandTop: 0,
      bandBottom: 0,
      bandHeight: 0,
    };
  }

  const latestTs = readings[readings.length - 1].timestamp;
  const windowMs = settings.graphHours * 3600_000;
  const cutoffTs = latestTs - windowMs;
  const visible = readings.filter((r) => r.timestamp >= cutoffTs);
  const series = visible.length >= 2 ? visible : readings.slice(-2);

  const values = series.map((r) => r.value);
  const rawMin = Math.min(...values, settings.lowThreshold - 10);
  const rawMax = Math.max(...values, settings.highThreshold + 15);
  const span = Math.max(40, rawMax - rawMin);
  const yMin = rawMin - span * 0.12;
  const yMax = rawMax + span * 0.14;

  const minTs = series[0].timestamp;
  const maxTs = series[series.length - 1].timestamp;
  const tsSpan = Math.max(1, maxTs - minTs);

  const padX = settings.graphStyle === "dots" ? 8 : 0;
  const plotW = VIEW_W - padX * 2;
  const padTop = 8;
  const padBottom = 6;
  const plotH = VIEW_H - padTop - padBottom;

  const toY = (mgDl: number): number => {
    const norm = (mgDl - yMin) / (yMax - yMin);
    return VIEW_H - padBottom - Math.max(0, Math.min(1, norm)) * plotH;
  };

  const points: GraphPoint[] = series.map((r) => ({
    x: padX + ((r.timestamp - minTs) / tsSpan) * plotW,
    y: toY(r.value),
    reading: r,
    color: statusColor(getGlucoseStatus(r.value, settings)),
  }));

  const linePath = buildSmoothLinePath(points);
  const lastPt = points[points.length - 1];
  const firstPt = points[0];
  const areaPath = `${linePath} L ${lastPt.x.toFixed(2)} ${VIEW_H} L ${firstPt.x.toFixed(2)} ${VIEW_H} Z`;

  const bandTop = toY(settings.highThreshold);
  const bandBottom = toY(settings.lowThreshold);
  const bandHeight = Math.max(0, bandBottom - bandTop);

  return {
    points,
    linePath,
    areaPath,
    bandTop,
    bandBottom,
    bandHeight,
  };
}

export function BackgroundGraph({
  layout,
  settings,
  accentColor,
  isLightTheme,
  hoveredIndex = null,
  isInspecting = false,
}: BackgroundGraphProps) {
  const uid = useId().replace(/:/g, "");
  const { points, linePath, areaPath, bandTop, bandBottom, bandHeight } = layout;

  if (!settings.showBackgroundGraph || points.length < 2) {
    return null;
  }

  const lastPt = points[points.length - 1];
  const hoveredPt =
    hoveredIndex !== null && hoveredIndex >= 0 && hoveredIndex < points.length
      ? points[hoveredIndex]
      : null;

  const fillGradId = `gm-fill-${uid}`;
  const glowId = `gm-glow-${uid}`;
  const guideStroke = isLightTheme ? "rgba(15, 23, 42, 0.14)" : "rgba(255, 255, 255, 0.12)";
  const bandFill = isLightTheme ? "rgba(15, 23, 42, 0.05)" : "rgba(255, 255, 255, 0.05)";
  const dotOutline = isLightTheme ? "rgba(248, 250, 252, 0.85)" : "rgba(9, 13, 22, 0.72)";
  const hoverRing = isLightTheme ? "#0f172a" : "#ffffff";

  const layerOpacity = isInspecting
    ? Math.max(settings.graphOpacity, 0.92)
    : settings.graphOpacity;

  return (
    <div
      className="bg-graph-layer"
      style={{ opacity: layerOpacity }}
      aria-hidden="true"
    >
      <svg
        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
        preserveAspectRatio="none"
        className="bg-graph-svg"
      >
        <defs>
          <linearGradient id={fillGradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={accentColor} stopOpacity="0.62" />
            <stop offset="70%" stopColor={accentColor} stopOpacity="0.16" />
            <stop offset="100%" stopColor={accentColor} stopOpacity="0.0" />
          </linearGradient>
          <filter id={glowId} x="-10%" y="-20%" width="120%" height="140%">
            <feGaussianBlur stdDeviation="2.2" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {settings.showTargetBand && bandHeight > 1 && (
          <>
            <rect
              x={0}
              y={bandTop}
              width={VIEW_W}
              height={bandHeight}
              fill={bandFill}
            />
            <line
              x1={0}
              y1={bandTop}
              x2={VIEW_W}
              y2={bandTop}
              stroke={guideStroke}
              strokeWidth="0.7"
              strokeDasharray="3 3"
            />
            <line
              x1={0}
              y1={bandBottom}
              x2={VIEW_W}
              y2={bandBottom}
              stroke={guideStroke}
              strokeWidth="0.7"
              strokeDasharray="3 3"
            />
          </>
        )}

        {settings.graphStyle === "smooth" ? (
          <>
            <path d={areaPath} fill={`url(#${fillGradId})`} />
            <path
              d={linePath}
              fill="none"
              stroke={accentColor}
              strokeWidth="2.1"
              strokeLinecap="round"
              strokeLinejoin="round"
              filter={`url(#${glowId})`}
            />
            <circle
              cx={Math.min(VIEW_W - 4, lastPt.x)}
              cy={lastPt.y}
              r="5"
              fill={accentColor}
              opacity="0.28"
            />
            <circle
              cx={Math.min(VIEW_W - 4, lastPt.x)}
              cy={lastPt.y}
              r="2.4"
              fill={accentColor}
            />
          </>
        ) : (
          <>
            <path d={areaPath} fill={`url(#${fillGradId})`} opacity="0.48" />
            <path
              d={linePath}
              fill="none"
              stroke={accentColor}
              strokeOpacity="0.2"
              strokeWidth="1"
            />

            {hoveredPt && (
              <line
                x1={hoveredPt.x}
                y1={hoveredPt.y}
                x2={hoveredPt.x}
                y2={VIEW_H}
                stroke={hoveredPt.color}
                strokeOpacity="0.45"
                strokeWidth="0.9"
                strokeDasharray="2.5 2.5"
              />
            )}

            {points.map((pt, idx) => {
              const isLatest = idx === points.length - 1;
              const isHovered = idx === hoveredIndex;
              if (isHovered) return null;
              return (
                <circle
                  key={pt.reading.timestamp}
                  cx={pt.x}
                  cy={pt.y}
                  r={isLatest ? 3.0 : 2.3}
                  fill={pt.color}
                  stroke={dotOutline}
                  strokeWidth="0.85"
                />
              );
            })}

            {hoveredPt && (
              <>
                <circle
                  cx={hoveredPt.x}
                  cy={hoveredPt.y}
                  r="7"
                  fill={hoveredPt.color}
                  opacity="0.3"
                />
                <circle
                  cx={hoveredPt.x}
                  cy={hoveredPt.y}
                  r="4.2"
                  fill={hoveredPt.color}
                  stroke={hoverRing}
                  strokeWidth="1.5"
                />
              </>
            )}
          </>
        )}
      </svg>
    </div>
  );
}
